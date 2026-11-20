package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.ProfitCalculator;
import io.qiao.dto.HoldingListView;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;

/**
 * 持股的新增、修改、刪除、查詢。
 * 網頁（第 2 題）和 API（第 5 題）共用這一層，規則只寫一次。
 */
@Service
public class HoldingService {

	private final HoldingRepository holdingRepository;
	private final StockLookupService stockLookupService;
	private final QuoteCache quoteCache;
	private final ProfitCalculator profitCalculator;
	private final Clock clock;

	public HoldingService(HoldingRepository holdingRepository, StockLookupService stockLookupService,
			QuoteCache quoteCache, ProfitCalculator profitCalculator, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.stockLookupService = stockLookupService;
		this.quoteCache = quoteCache;
		this.profitCalculator = profitCalculator;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Holding> findAll() {
		return holdingRepository.findAllByOrderByCodeAsc();
	}

	/**
	 * 清單用：每筆持股加上快取裡的行情與盈虧。只讀記憶體快取，不會向官方查詢（題目第 3 題要求 3）。
	 */
	@Transactional(readOnly = true)
	public List<HoldingView> findAllViews() {
		return holdingRepository.findAllByOrderByCodeAsc().stream()
				.map(holding -> HoldingView.of(holding, quoteCache.get(holding.getCode()).orElse(null), profitCalculator))
				.toList();
	}

	/**
	 * 清單頁用：每一筆持股＋頁面最上方的總計（第 4 題）。
	 * 清單和總計用同一份資料算出來，數字一定一致。
	 */
	@Transactional(readOnly = true)
	public HoldingListView findListWithSummary() {
		return HoldingListView.of(findAllViews());
	}

	@Transactional(readOnly = true)
	public Holding findById(Long id) {
		return holdingRepository.findById(id)
				.orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * 新增持股：先檢查重複，再向官方確認代號存在（並取得名稱、市場別、最近收盤價），最後儲存。
	 *
	 * 注意：這個方法沒有加 @Transactional。查官方最多要等 9 秒，
	 * 不應該在這段時間佔用資料庫連線（交易開始就會佔用一條連線）；真正寫入時 saveAndFlush 會自己開交易。
	 *
	 * @throws DuplicateCodeException     代號已存在
	 * @throws StockNotFoundException     上市、上櫃都查不到這個代號（資料不儲存）
	 * @throws QuoteUnavailableException 官方連不上，無法確認代號是否存在（資料不儲存）
	 */
	public Holding create(String code, Integer lots, BigDecimal buyPrice) {
		String normalizedCode = normalizeCode(code);

		// 第一道檢查：先查有沒有重複（不用浪費一次官方查詢），給使用者清楚的錯誤訊息
		if (holdingRepository.existsByCode(normalizedCode)) {
			throw new DuplicateCodeException(normalizedCode);
		}

		// 向官方確認代號存在，同時判斷上市／上櫃（查不到或連不上會丟例外，資料就不會儲存）
		StockQuote quote = stockLookupService.lookup(normalizedCode);

		LocalDateTime now = LocalDateTime.now(clock);
		Holding holding = new Holding();
		holding.setCode(normalizedCode);
		holding.setName(quote.name());
		holding.setMarket(quote.market());
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		// 順便記下最近收盤價，之後官方連不上時可以顯示「上次收盤價」
		holding.setLastClosePrice(quote.closePrice());
		holding.setLastPriceDate(quote.priceDate());
		holding.setCreatedAt(now);
		holding.setUpdatedAt(now);

		Holding saved;
		try {
			// saveAndFlush：立刻寫入資料庫，讓 UNIQUE 限制的錯誤在這裡就發生，才能轉成 DuplicateCodeException
			saved = holdingRepository.saveAndFlush(holding);
		} catch (DataIntegrityViolationException e) {
			// 第二道檢查（最後防線）：兩個人「同時」新增同一個代號時，第一道檢查都會通過，由資料庫的 UNIQUE 限制擋下
			throw new DuplicateCodeException(normalizedCode);
		}

		// 剛查到的行情直接放進快取：新增完回到清單就看得到收盤價，不用等排程
		quoteCache.putSuccess(quote, now);
		return saved;
	}

	/**
	 * 修改持股：只能改張數和買進價，代號不能改（與 App 1.0 API 規格一致）。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public Holding update(Long id, Integer lots, BigDecimal buyPrice) {
		Holding holding = findById(id);
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		holding.setUpdatedAt(LocalDateTime.now(clock));
		// 在交易（@Transactional）內修改查出來的 Entity，結束時 JPA 會自動寫回資料庫，不需要再呼叫 save
		return holding;
	}

	/**
	 * 刪除持股。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public void delete(Long id) {
		Holding holding = findById(id);
		holdingRepository.delete(holding);
		// 同一個代號只會有一筆持股，刪掉後快取也不需要了（排程只會查還在的持股）
		quoteCache.remove(holding.getCode());
	}

	/**
	 * 背景排程查到行情後呼叫：
	 * 1. 補上或更新名稱與市場：第 2 題時新增、還沒有名稱或市場的持股；
	 *    或上櫃轉上市（或反過來）時，代號不變但市場改變。
	 * 2. 更新「上次收盤價」（只在數字或日期有變時才寫入）。
	 * 不更新 updated_at：那個欄位代表「使用者最後修改的時間」，排程更新行情不算使用者修改。
	 * 持股在排程查詢期間被刪除時，直接略過。
	 */
	@Transactional
	public void recordQuote(Long holdingId, StockQuote quote) {
		holdingRepository.findById(holdingId).ifPresent(holding -> {
			if (holding.getName() == null || holding.getMarket() != quote.market()) {
				holding.setName(quote.name());
				holding.setMarket(quote.market());
			}
			boolean priceChanged = holding.getLastClosePrice() == null
					|| holding.getLastClosePrice().compareTo(quote.closePrice()) != 0
					|| !Objects.equals(holding.getLastPriceDate(), quote.priceDate());
			if (priceChanged) {
				holding.setLastClosePrice(quote.closePrice());
				holding.setLastPriceDate(quote.priceDate());
			}
		});
	}

	/**
	 * 統一代號格式：去掉前後空白、英文轉大寫（例如「 00632r 」→「00632R」），
	 * 避免同一檔股票因為大小寫或空白不同而被存成兩筆。
	 */
	private String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}
}
