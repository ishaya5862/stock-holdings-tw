package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.calculation.ProfitCalculator;
import io.qiao.calculation.TradeSide;
import io.qiao.dto.HoldingListView;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.entity.Stock;
import io.qiao.entity.Trade;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;
import io.qiao.validation.StockCodes;

/**
 * 持股的新增、修改、刪除、查詢。
 * 網頁（第 2 題）和 API（第 5 題）共用這一層，規則只寫一次。
 *
 * 持股（holdings）只存使用者會改的欄位；股票名稱、市場別、上次收盤價在 stocks（StockService 負責寫入）。
 *
 * 2.0 過渡期（第 6 題 C2，「擴充 → 遷移 → 收縮」的雙寫階段）：
 * 1.0 的新增、修改、刪除除了寫 holdings 的張數、買進價，也同步寫 trades（每筆持股剛好一筆買進），
 * 讓每個版本的資料都一致。C4 改成由 trades 計算輸出後，再刪除 holdings 的舊欄位（V6）。
 */
@Service
public class HoldingService {

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final StockService stockService;
	private final StockLookupService stockLookupService;
	private final QuoteCache quoteCache;
	private final ProfitCalculator profitCalculator;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public HoldingService(HoldingRepository holdingRepository, TradeRepository tradeRepository, StockService stockService,
			StockLookupService stockLookupService, QuoteCache quoteCache, ProfitCalculator profitCalculator,
			TransactionTemplate transactionTemplate, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.stockService = stockService;
		this.stockLookupService = stockLookupService;
		this.quoteCache = quoteCache;
		this.profitCalculator = profitCalculator;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Holding> findAll() {
		return holdingRepository.findAllByOrderByCodeAsc();
	}

	/**
	 * 清單用：每筆持股＋股票資訊＋快取裡的行情＋盈虧。
	 * 只讀資料庫與記憶體快取，不會向官方查詢（題目第 3 題要求 3）。
	 * 股票資訊一次查出所有代號再配對，查詢次數固定 2 次，不會每一筆持股各查一次。
	 */
	@Transactional(readOnly = true)
	public List<HoldingView> findAllViews() {
		List<Holding> holdings = holdingRepository.findAllByOrderByCodeAsc();
		Map<String, Stock> stocks = stockService.findByCodes(holdings.stream().map(Holding::getCode).toList());
		return holdings.stream()
				.map(holding -> HoldingView.of(holding, stocks.get(holding.getCode()),
						quoteCache.get(holding.getCode()).orElse(null), profitCalculator))
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

	/**
	 * 單一持股（含股票資訊、行情、盈虧）：App 的修改畫面用（GET /api/v1/holdings/{id}）。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional(readOnly = true)
	public HoldingView findViewById(Long id) {
		Holding holding = findById(id);
		Stock stock = stockService.findByCodes(List.of(holding.getCode())).get(holding.getCode());
		return HoldingView.of(holding, stock, quoteCache.get(holding.getCode()).orElse(null), profitCalculator);
	}

	@Transactional(readOnly = true)
	public Holding findById(Long id) {
		return holdingRepository.findById(id)
				.orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * 新增持股：先檢查重複，再向官方確認代號存在（並取得名稱、市場別、最近收盤價），最後儲存。
	 *
	 * 寫入順序：先寫 stocks（股票資訊），再寫 holdings（外鍵規定持股的代號一定要在股票表裡），最後寫 trades（一筆買進）。
	 *
	 * 注意：這個方法沒有加 @Transactional。查官方最多要等 9 秒，
	 * 不應該在這段時間佔用資料庫連線（交易開始就會佔用一條連線）；查完官方後，三個寫入用 TransactionTemplate
	 * 包成同一個交易：任何一步失敗就全部復原，不會出現「有持股、沒有交易紀錄」。
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
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		holding.setCreatedAt(now);
		holding.setUpdatedAt(now);

		Holding saved;
		try {
			saved = transactionTemplate.execute(status -> {
				// 股票資訊（名稱、市場、上次收盤價）寫到 stocks；已有這個代號就只更新
				stockService.recordQuote(quote);
				// saveAndFlush：立刻寫入資料庫，讓 UNIQUE 限制的錯誤在這裡就發生，才能轉成 DuplicateCodeException
				Holding inserted = holdingRepository.saveAndFlush(holding);
				// 2.0 雙寫：同一筆持股的買進紀錄（App 1.0 沒有日期 → 台北時區的今天，標記推估）
				tradeRepository.save(Trade.v1Buy(inserted.getId(), lots, buyPrice, now));
				return inserted;
			});
		} catch (DataIntegrityViolationException e) {
			// 第二道檢查（最後防線）：兩個人「同時」新增同一個代號時，第一道檢查都會通過，由資料庫的 UNIQUE／主鍵限制擋下
			throw new DuplicateCodeException(normalizedCode);
		}

		// 剛查到的行情直接放進快取：新增完回到清單就看得到收盤價，不用等排程
		quoteCache.putSuccess(quote, now);
		return saved;
	}

	/**
	 * 修改持股：只能改張數和買進價，代號不能改（與 App 1.0 API 規格一致）。
	 * 只會寫到 holdings；股票資訊在 stocks，背景排程同時寫行情也不會互相覆寫。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public Holding update(Long id, Integer lots, BigDecimal buyPrice) {
		Holding holding = findById(id);
		LocalDateTime now = LocalDateTime.now(clock);
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		holding.setUpdatedAt(now);

		// 2.0 雙寫：1.0 的持股剛好對應一筆買進，同步修改它的股數與價格（日期不變）
		// 有多筆交易或有賣出的持股是 2.0 才會出現的情況，v1 怎麼處理在 C4 決定；這裡先明確擋下，不默默改壞資料
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id);
		if (trades.size() != 1 || trades.get(0).getSide() != TradeSide.BUY) {
			throw new IllegalStateException("持股 " + id + " 不是「只有一筆買進」，1.0 的修改方式不適用（C4 處理）");
		}
		Trade trade = trades.get(0);
		trade.setShares((long) lots * 1000);
		trade.setPrice(buyPrice);
		trade.setUpdatedAt(now);

		// 在交易（@Transactional）內修改查出來的 Entity，結束時 JPA 會自動寫回資料庫，不需要再呼叫 save
		return holding;
	}

	/**
	 * 刪除持股（連同它的全部交易紀錄）。股票資訊（stocks）保留：排程只查有人持有的股票。
	 * 外鍵不設連帶刪除（C2-2），所以這裡明確「先刪交易、再刪持股」，刪除範圍寫在程式裡看得見。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public void delete(Long id) {
		Holding holding = findById(id);
		tradeRepository.deleteByHoldingId(id);
		holdingRepository.delete(holding);
		// 同一個代號只會有一筆持股，刪掉後快取也不需要了
		quoteCache.remove(holding.getCode());
	}

	/**
	 * 統一代號格式（規則在 StockCodes.normalize：去空白、全形轉半形、轉大寫，例如「００６３２ｒ」→「00632R」），
	 * 避免同一檔股票因為大小寫、全形或空白不同而被存成兩筆。網頁和 App API 都經過這裡。
	 */
	private String normalizeCode(String code) {
		return StockCodes.normalize(code);
	}
}
