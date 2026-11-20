package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.calculation.OversellException;
import io.qiao.calculation.PositionCalculator;
import io.qiao.dto.HoldingListView;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.entity.Stock;
import io.qiao.entity.Trade;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.UpgradeRequiredException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;
import io.qiao.validation.StockCodes;

/**
 * 1.0 介面（App 1.0 的 /api/v1、第一版網頁）的持股新增、修改、刪除、查詢，規則只寫一次。
 *
 * 2.0（第 6 題 C4）起：
 * - 讀取：張數、買進價、盈虧都由交易紀錄（trades）重算（PositionCalculator），再換成 1.0 的樣子（HoldingView）。
 * - 全部賣光（0 股）的持股：1.0 看不到（清單、總計不出現，單筆、修改、刪除都當成找不到）（C4-5）。
 * - 新增：代號已有持股 → 409（v1 規格）；已全部賣光 → 沿用原本的持股列，加一筆買進（C4-5）。
 * - 修改：只有「整張、一筆買進」（1.0 可以完整表達）時允許，改那一筆買進；其他拒絕並請使用者更新到 2.0（C4-3）。
 * - 刪除：只有一筆交易時允許；其他拒絕，避免 1.0 使用者誤刪 2.0 的買賣紀錄（C4-4）。
 *
 * 股票名稱、市場別、上次收盤價在 stocks（StockService 負責寫入）。
 */
@Service
public class HoldingService {

	private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final HoldingLock holdingLock;
	private final StockService stockService;
	private final StockLookupService stockLookupService;
	private final QuoteCache quoteCache;
	private final PositionCalculator positionCalculator;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public HoldingService(HoldingRepository holdingRepository, TradeRepository tradeRepository, HoldingLock holdingLock,
			StockService stockService, StockLookupService stockLookupService, QuoteCache quoteCache,
			PositionCalculator positionCalculator, TransactionTemplate transactionTemplate, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.holdingLock = holdingLock;
		this.stockService = stockService;
		this.stockLookupService = stockLookupService;
		this.quoteCache = quoteCache;
		this.positionCalculator = positionCalculator;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	/**
	 * 清單用：每筆持股＋股票資訊＋快取裡的行情＋由交易重算的結果。0 股的持股不出現（C4-5）。
	 * 只讀資料庫與記憶體快取，不會向官方查詢（題目第 3 題要求 3）。
	 * 持股、股票、交易各一次查詢後在記憶體配對，查詢次數固定 3 次，不會每一筆持股各查一次。
	 */
	@Transactional(readOnly = true)
	public List<HoldingView> findAllViews() {
		List<Holding> holdings = holdingRepository.findAllByOrderByCodeAsc();
		Map<String, Stock> stocks = stockService.findByCodes(holdings.stream().map(Holding::getCode).toList());
		Map<Long, List<Trade>> tradesByHolding = tradeRepository.findAllByOrderByHoldingIdAscTradeDateAscIdAsc().stream()
				.collect(Collectors.groupingBy(Trade::getHoldingId));
		LocalDate today = LocalDate.now(clock);
		return holdings.stream()
				.map(holding -> toView(holding, stocks.get(holding.getCode()),
						tradesByHolding.getOrDefault(holding.getId(), List.of()), today))
				// 0 股的不顯示（C4-5）；資料異常的保留給第一版網頁顯示原因（v1 API 另外排除，1-2 a）
				.filter(view -> view.shares() > 0 || view.hasDataError())
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
	 * 單一持股：App 的修改畫面用（GET /api/v1/holdings/{id}）、網頁修改頁用。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股、已全部賣光（1.0 看不到 0 股的持股），
	 *                                  或資料異常（1.0 不輸出這一檔，1-2 a；修改、刪除也因此擋下）
	 */
	@Transactional(readOnly = true)
	public HoldingView findViewById(Long id) {
		Holding holding = findById(id);
		Stock stock = stockService.findByCodes(List.of(holding.getCode())).get(holding.getCode());
		HoldingView view = toView(holding, stock, tradesOf(id), LocalDate.now(clock));
		if (view.shares() == 0 || view.hasDataError()) {
			throw new HoldingNotFoundException(id);
		}
		return view;
	}

	@Transactional(readOnly = true)
	public Holding findById(Long id) {
		return holdingRepository.findById(id)
				.orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * 新增持股（1.0：張數＋買進價）。
	 * - 代號已有持股（股數 > 0）→ DuplicateCodeException（v1 規格 409）
	 * - 代號曾經持有、已全部賣光 → 沿用原本的持股列，加一筆買進（C4-5；1.0 使用者看起來就是「新增」）
	 * - 從沒持有過 → 向官方確認代號存在（並取得名稱、市場別、最近收盤價），建立持股列＋一筆買進
	 *
	 * 注意：這個方法沒有加 @Transactional。查官方最多要等 9 秒，
	 * 不應該在這段時間佔用資料庫連線（交易開始就會佔用一條連線）；查完官方後，寫入用 TransactionTemplate
	 * 包成同一個交易：任何一步失敗就全部復原，不會出現「有持股、沒有交易紀錄」。
	 *
	 * @throws DuplicateCodeException     代號已有持股
	 * @throws StockNotFoundException     上市、上櫃都查不到這個代號（資料不儲存）
	 * @throws QuoteUnavailableException 官方連不上，無法確認代號是否存在（資料不儲存）
	 */
	public Holding create(String code, Integer lots, BigDecimal buyPrice) {
		String normalizedCode = normalizeCode(code);
		LocalDateTime now = LocalDateTime.now(clock);

		// 第一道檢查：先查有沒有這個代號（不用浪費一次官方查詢），給使用者清楚的錯誤訊息
		Optional<Holding> existing = holdingRepository.findByCode(normalizedCode);
		if (existing.isPresent()) {
			return rebuySoldOut(existing.get(), lots, buyPrice, now);
		}

		// 向官方確認代號存在，同時判斷上市／上櫃（查不到或連不上會丟例外，資料就不會儲存）
		StockQuote quote = stockLookupService.lookup(normalizedCode);

		Holding holding = new Holding();
		holding.setCode(normalizedCode);
		holding.setCreatedAt(now);
		holding.setUpdatedAt(now);

		Holding saved;
		try {
			saved = transactionTemplate.execute(status -> {
				// 股票資訊（名稱、市場、上次收盤價）寫到 stocks；已有這個代號就只更新
				stockService.recordQuote(quote);
				// saveAndFlush：立刻寫入資料庫，讓 UNIQUE 限制的錯誤在這裡就發生，才能轉成 DuplicateCodeException
				Holding inserted = holdingRepository.saveAndFlush(holding);
				// 1.0 只有張數、買進價：記成一筆買進（沒有日期 → 台北時區的今天，標記推估）
				tradeRepository.save(Trade.v1Buy(inserted.getId(), lots, buyPrice, now));
				return inserted;
			});
		} catch (DataIntegrityViolationException e) {
			// 第二道檢查（最後防線）：兩個人「同時」新增同一個代號時，第一道檢查都會通過，由資料庫的 UNIQUE 限制擋下
			throw new DuplicateCodeException(normalizedCode);
		}

		// 剛查到的行情直接放進快取：新增完回到清單就看得到收盤價，不用等排程
		quoteCache.putSuccess(quote, now);
		return saved;
	}

	/** 代號已有持股列：還有股數 → 重複；已全部賣光 → 沿用這一列，加一筆買進（C4-5） */
	private Holding rebuySoldOut(Holding holding, Integer lots, BigDecimal buyPrice, LocalDateTime now) {
		return transactionTemplate.execute(status -> {
			// 鎖住後才判斷股數：避免同時有 2.0 的買進進來（C3，和交易服務同一套鎖）
			Holding locked = holdingLock.byId(holding.getId(), () -> new HoldingNotFoundException(holding.getId()));
			if (currentShares(locked) > 0) {
				throw new DuplicateCodeException(locked.getCode());
			}
			tradeRepository.save(Trade.v1Buy(locked.getId(), lots, buyPrice, now));
			locked.setUpdatedAt(now);
			return locked;
		});
	}

	/**
	 * 修改持股（1.0：張數＋買進價，代號不能改）。
	 * 只有 1.0 可以完整表達的持股（整張、只有一筆買進）才允許，改那一筆買進的股數、價格（日期不變）。
	 *
	 * @throws HoldingNotFoundException  找不到這筆持股，或已全部賣光
	 * @throws UpgradeRequiredException 這筆持股有零股、多次買進或賣出紀錄（C4-3，API 回 409）
	 */
	@Transactional
	public Holding update(Long id, Integer lots, BigDecimal buyPrice) {
		// 先鎖住（C3）：1.0 修改和 2.0 的買賣同時發生時排隊，讀到的一定是最新的交易紀錄
		Holding holding = holdingLock.byId(id, () -> new HoldingNotFoundException(id));
		HoldingView view = findViewById(id);
		if (!view.v1Representable()) {
			throw new UpgradeRequiredException(holding.getCode(), "修改", view.v1Limits());
		}

		LocalDateTime now = LocalDateTime.now(clock);
		Trade trade = tradesOf(id).get(0); // 1.0 可以完整表達 → 剛好一筆買進
		trade.setShares((long) lots * 1000);
		trade.setPrice(buyPrice);
		trade.setUpdatedAt(now);
		holding.setUpdatedAt(now);
		// 在交易（@Transactional）內修改查出來的 Entity，結束時 JPA 會自動寫回資料庫，不需要再呼叫 save
		return holding;
	}

	/**
	 * 刪除持股（連同它的交易紀錄）。股票資訊（stocks）保留：排程只查有人持有的股票。
	 * 只有一筆交易時允許（C4-4）；外鍵不設連帶刪除（C2-2），所以這裡明確「先刪交易、再刪持股」。
	 *
	 * @throws HoldingNotFoundException  找不到這筆持股，或已全部賣光
	 * @throws UpgradeRequiredException 這筆持股有多筆交易（避免 1.0 使用者誤刪 2.0 的買賣紀錄，API 回 409）
	 */
	@Transactional
	public void delete(Long id) {
		Holding holding = holdingLock.byId(id, () -> new HoldingNotFoundException(id)); // 先鎖住（C3）
		HoldingView view = findViewById(id); // 0 股 → 找不到（1.0 看不到）
		if (tradesOf(id).size() != 1) {
			throw new UpgradeRequiredException(holding.getCode(), "刪除", view.v1Limits());
		}
		tradeRepository.deleteByHoldingId(id);
		holdingRepository.delete(holding);
		// 同一個代號只會有一筆持股，刪掉後快取也不需要了
		quoteCache.remove(holding.getCode());
	}

	private HoldingView toView(Holding holding, Stock stock, List<Trade> trades, LocalDate today) {
		HoldingView view = HoldingView.of(holding, stock, quoteCache.get(holding.getCode()).orElse(null), trades,
				positionCalculator, today);
		if (view.hasDataError()) {
			// 1-3 a：記錄錯誤 log（代號、持股 id、原因），維運人員才知道哪一檔需要修正
			log.error("持股資料異常，無法計算：代號 {}、持股 id {}、原因：{}", holding.getCode(), holding.getId(), view.dataError());
		}
		return view;
	}

	private List<Trade> tradesOf(Long holdingId) {
		return tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId);
	}

	/**
	 * 目前股數（依交易重算；不需要收盤價）。
	 * 資料異常（時間線不合理）→ 1.0 不處理這一檔，請使用者用 2.0 修正（和修改、刪除相同的訊息）。
	 */
	private long currentShares(Holding holding) {
		try {
			return positionCalculator.calculate(holding.getCode(),
					tradesOf(holding.getId()).stream().map(Trade::toInput).toList(), null, LocalDate.now(clock)).shares();
		} catch (OversellException e) {
			log.error("持股資料異常，無法計算：代號 {}、持股 id {}、原因：{}", holding.getCode(), holding.getId(), e.getMessage());
			throw new UpgradeRequiredException(holding.getCode(), "新增", List.of("資料異常"));
		}
	}

	/**
	 * 統一代號格式（規則在 StockCodes.normalize：去空白、全形轉半形、轉大寫，例如「００６３２ｒ」→「00632R」），
	 * 避免同一檔股票因為大小寫、全形或空白不同而被存成兩筆。網頁和 App API 都經過這裡。
	 */
	private String normalizeCode(String code) {
		return StockCodes.normalize(code);
	}
}
