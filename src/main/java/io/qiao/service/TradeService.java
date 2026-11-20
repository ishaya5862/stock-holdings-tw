package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.calculation.PositionCalculator;
import io.qiao.calculation.PositionResult;
import io.qiao.calculation.TradeInput;
import io.qiao.calculation.TradeSide;
import io.qiao.entity.Holding;
import io.qiao.entity.Trade;
import io.qiao.exception.InvalidTradeException;
import io.qiao.exception.NotHeldException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.exception.TradeVersionConflictException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;
import io.qiao.validation.StockCodes;

/**
 * 2.0 的交易服務（第 6 題 C3）：買進、賣出、修改一筆交易、刪除一筆交易。App 2.0（C6）與新網頁（C9）共用。
 *
 * 每一次寫入都照同一個流程（PARTB_NOTES B-17、B-20 的決定）：
 * 1. 檢查股數、價格、日期（規則見 validate）
 * 2. 開交易 → 鎖住那一檔持股（HoldingLock，悲觀鎖；等超過 3 秒 → 409 請稍後再試）
 * 3. 讀這一檔「最新」的全部交易，加上這次的變動，依日期整條重算（PositionCalculator）
 *    → 任何時間點賣超 → OversellException，什麼都不寫（交易復原）
 * 4. 寫入，更新持股的修改時間（版本號自動 +1），回傳重算後的結果
 *
 * 識別方式（C3-8）：買進、賣出用股票代號（使用者的想法就是「買 2330」）；修改、刪除用交易 id＋版本號（C3-5）。
 */
@Service
public class TradeService {

	/** 每筆交易的股數上限（V-b：防打錯，一般投資人碰不到；1.0 的 1～999 張另外在 v1 檢查） */
	public static final long MAX_SHARES = 99_999_999L;
	/** 價格上限（和資料表 DECIMAL(10,2) 一致） */
	public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
	/** 最早的交易日期（題目 1 a；資料庫也有同樣的 CHECK） */
	public static final LocalDate EARLIEST_DATE = LocalDate.of(1990, 1, 1);

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final HoldingLock holdingLock;
	private final StockService stockService;
	private final StockLookupService stockLookupService;
	private final QuoteCache quoteCache;
	private final PositionCalculator positionCalculator;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public TradeService(HoldingRepository holdingRepository, TradeRepository tradeRepository, HoldingLock holdingLock,
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

	// ===== 買進 =====

	/**
	 * 買進。還沒持有的代號會先向官方確認存在（最多 9 秒，在交易外，不佔資料庫連線），再建立持股；
	 * 已持有或已全部賣光的代號直接加一筆買進（不查官方）。
	 *
	 * @throws io.qiao.exception.StockNotFoundException     上市、上櫃都查不到這個代號
	 * @throws io.qiao.exception.QuoteUnavailableException 官方連不上，無法確認代號
	 */
	public TradeResult buy(String code, LocalDate tradeDate, long shares, BigDecimal price) {
		String normalizedCode = StockCodes.normalize(code);
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);

		if (holdingRepository.findByCode(normalizedCode).isEmpty()) {
			StockQuote quote = stockLookupService.lookup(normalizedCode); // 交易外：查官方時不佔資料庫連線
			Optional<TradeResult> created = createHoldingAndBuy(quote, tradeDate, shares, price, now);
			quoteCache.putSuccess(quote, now); // 剛查到的行情放進快取，回傳結果就有未實現損益
			if (created.isPresent()) {
				return recalculated(created.get());
			}
			// C3-4：建立持股時撞到「代號唯一」→ 另一個裝置剛好同時建立了這檔 → 改成加到已存在的持股
		}
		return recalculated(inTransaction(
				() -> addTrade(lockByCode(normalizedCode), TradeSide.BUY, tradeDate, shares, price, now)));
	}

	/** 建立持股＋第一筆買進（同一個交易）；代號已被另一個請求建立 → empty */
	private Optional<TradeResult> createHoldingAndBuy(StockQuote quote, LocalDate tradeDate, long shares,
			BigDecimal price, LocalDateTime now) {
		try {
			return Optional.of(inTransaction(() -> {
				stockService.recordQuote(quote); // 先寫股票表（外鍵：持股的代號一定要在股票表裡）
				Holding holding = new Holding();
				holding.setCode(quote.code());
				holding.setCreatedAt(now);
				holding.setUpdatedAt(now);
				// saveAndFlush：立刻寫入，讓 UNIQUE 限制的錯誤在這裡發生
				return addTrade(holdingRepository.saveAndFlush(holding), TradeSide.BUY, tradeDate, shares, price, now);
			}));
		} catch (DataIntegrityViolationException e) {
			return Optional.empty();
		}
	}

	// ===== 賣出 =====

	/**
	 * 賣出一部分或全部。
	 *
	 * @throws NotHeldException                          從沒持有過這個代號
	 * @throws io.qiao.calculation.OversellException 賣出當時持有的股數不夠（含補登過去日期的賣出）
	 */
	public TradeResult sell(String code, LocalDate tradeDate, long shares, BigDecimal price) {
		String normalizedCode = StockCodes.normalize(code);
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);
		return recalculated(inTransaction(
				() -> addTrade(lockByCode(normalizedCode), TradeSide.SELL, tradeDate, shares, price, now)));
	}

	/** 在已鎖住的持股加一筆交易：先整條重算驗證（賣超就丟例外、不寫入），通過才寫入 */
	private TradeResult addTrade(Holding holding, TradeSide side, LocalDate tradeDate, long shares,
			BigDecimal price, LocalDateTime now) {
		List<TradeInput> inputs = new ArrayList<>(inputsOf(holding.getId()));
		inputs.add(new TradeInput(null, side, tradeDate, shares, price)); // 還沒存檔：排在同一天最後
		calculate(holding, inputs); // 賣超 → OversellException，下面都不會執行

		Trade trade = tradeRepository.saveAndFlush(Trade.create(holding.getId(), side, tradeDate, shares, price, now));
		holding.setUpdatedAt(now); // 持股版本號 +1（C3-9）
		return new TradeResult(holding, trade, null, false);
	}

	// ===== 修改、刪除一筆交易 =====

	/**
	 * 修改一筆交易的日期、股數、價格（買賣種類、代號不能改，C3-3）。改了日期 → 不再是推估（D3）。
	 *
	 * @param expectedVersion 用戶端看到的版本號；和資料庫不同 → 另一個裝置已經改過（C3-5）
	 * @throws TradeNotFoundException        找不到這筆交易
	 * @throws TradeVersionConflictException 版本號不符
	 * @throws io.qiao.calculation.OversellException 改完之後某一筆賣出變成賣超
	 */
	public TradeResult updateTrade(Long tradeId, Long expectedVersion, LocalDate tradeDate, long shares,
			BigDecimal price) {
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);
		return recalculated(inTransaction(() -> {
			Holding holding = lockHoldingOfTrade(tradeId);
			Trade trade = loadWithVersion(tradeId, expectedVersion);

			List<TradeInput> inputs = new ArrayList<>(inputsOf(holding.getId()).stream()
					.filter(input -> !input.id().equals(tradeId)).toList());
			inputs.add(new TradeInput(tradeId, trade.getSide(), tradeDate, shares, price));
			calculate(holding, inputs); // 改完會賣超 → 例外，交易紀錄不變

			trade.setTradeDate(tradeDate);
			trade.setShares(shares);
			trade.setPrice(price);
			trade.setUpdatedAt(now);
			holding.setUpdatedAt(now);
			tradeRepository.flush(); // 立刻寫入：交易的版本號 +1，回傳的才是新版本
			return new TradeResult(holding, trade, null, false);
		}));
	}

	/**
	 * 刪除一筆交易。刪掉的是最後一筆 → 持股列也一起刪除（C2-1）。
	 *
	 * @throws TradeNotFoundException        找不到這筆交易
	 * @throws TradeVersionConflictException 版本號不符
	 * @throws io.qiao.calculation.OversellException 刪掉這筆買進後，之後的賣出變成賣超
	 */
	public TradeResult deleteTrade(Long tradeId, Long expectedVersion) {
		LocalDateTime now = LocalDateTime.now(clock);
		TradeResult result = inTransaction(() -> {
			Holding holding = lockHoldingOfTrade(tradeId);
			Trade trade = loadWithVersion(tradeId, expectedVersion);

			List<TradeInput> remaining = inputsOf(holding.getId()).stream()
					.filter(input -> !input.id().equals(tradeId)).toList();
			if (remaining.isEmpty()) {
				// 最後一筆：沒有交易就沒有持股。外鍵不連帶刪除（C2-2），先刪交易、再刪持股
				tradeRepository.delete(trade);
				holdingRepository.delete(holding);
				quoteCache.remove(holding.getCode());
				return new TradeResult(holding, null, null, true);
			}
			calculate(holding, remaining); // 刪掉後會賣超 → 例外，什麼都不刪

			tradeRepository.delete(trade);
			holding.setUpdatedAt(now);
			return new TradeResult(holding, null, null, false);
		});
		return result.holdingDeleted() ? result : recalculated(result);
	}

	/** 交易 → 所屬持股 → 鎖住。只查持股 id（不載入交易），鎖住之後才載入交易，讀到的才是最新版本 */
	private Holding lockHoldingOfTrade(Long tradeId) {
		Long holdingId = tradeRepository.findHoldingIdById(tradeId)
				.orElseThrow(() -> new TradeNotFoundException(tradeId));
		// 查到 id 之後、鎖住之前，另一個裝置可能剛好刪掉最後一筆交易（持股也被刪）→ 一樣當成找不到交易
		return holdingLock.byId(holdingId, () -> new TradeNotFoundException(tradeId));
	}

	private Trade loadWithVersion(Long tradeId, Long expectedVersion) {
		Trade trade = tradeRepository.findById(tradeId).orElseThrow(() -> new TradeNotFoundException(tradeId));
		if (expectedVersion == null || !expectedVersion.equals(trade.getVersion())) {
			throw new TradeVersionConflictException(tradeId);
		}
		return trade;
	}

	// ===== 規則 =====

	/**
	 * 股數、價格、日期的硬性規則（不合格就不儲存）：
	 * | 股數 | 1～99,999,999 股（超過時說明「數量更大請分成多筆記錄」，P2）               |
	 * | 價格 | 大於 0、最多兩位小數、最大 99,999,999.99                                    |
	 * | 日期 | 1990-01-01 起，不能晚於「台北時間」的今天（主機在海外也以台北為準）          |
	 * 週末、休市日都可以儲存（題目 1 a）；「這天可能不是交易日，確定嗎？」的提醒由前端負責（題目 1 d，使用者決定）。
	 */
	void validate(LocalDate tradeDate, long shares, BigDecimal price) {
		if (shares < 1) {
			throw new InvalidTradeException("shares", "股數最少 1 股");
		}
		if (shares > MAX_SHARES) {
			throw new InvalidTradeException("shares", "單筆最多 99,999,999 股；數量更大請分成多筆記錄");
		}
		if (price == null || price.signum() <= 0) {
			throw new InvalidTradeException("price", "價格必須大於 0");
		}
		if (price.stripTrailingZeros().scale() > 2) {
			throw new InvalidTradeException("price", "價格最多兩位小數");
		}
		if (price.compareTo(MAX_PRICE) > 0) {
			throw new InvalidTradeException("price", "價格最大 99,999,999.99");
		}
		if (tradeDate == null) {
			throw new InvalidTradeException("trade_date", "請輸入交易日期");
		}
		if (tradeDate.isBefore(EARLIEST_DATE)) {
			throw new InvalidTradeException("trade_date", "交易日期不能早於 1990-01-01");
		}
		LocalDate today = LocalDate.now(clock);
		if (tradeDate.isAfter(today)) {
			throw new InvalidTradeException("trade_date", "交易日期不能晚於今天（台北時間 " + today + "）");
		}
	}

	// ===== 共用 =====

	private Holding lockByCode(String code) {
		return holdingLock.byCode(code, () -> new NotHeldException(code));
	}

	private List<TradeInput> inputsOf(Long holdingId) {
		return tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId).stream().map(Trade::toInput).toList();
	}

	/** 重算（賣超會丟 OversellException）；有今天的收盤價（快取 OK）才算未實現損益 */
	private PositionResult calculate(Holding holding, List<TradeInput> inputs) {
		return positionCalculator.calculate(holding.getCode(), inputs, closePrice(holding.getCode()),
				LocalDate.now(clock));
	}

	private BigDecimal closePrice(String code) {
		return quoteCache.get(code)
				.filter(entry -> entry.status() == QuoteStatus.OK && entry.quote() != null)
				.map(entry -> entry.quote().closePrice())
				.orElse(null);
	}

	/** 交易提交後，用資料庫裡的最終資料重算一次（新交易已有 id，明細的順序和之後查詢的一致） */
	private TradeResult recalculated(TradeResult written) {
		Holding holding = written.holding();
		PositionResult position = calculate(holding, inputsOf(holding.getId()));
		return new TradeResult(holding, written.trade(), position, false);
	}

	private <T> T inTransaction(Supplier<T> work) {
		return transactionTemplate.execute(status -> work.get());
	}
}
