package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.calculation.OversellException;
import io.qiao.calculation.PositionCalculator;
import io.qiao.calculation.PositionResult;
import io.qiao.calculation.TradeInput;
import io.qiao.calculation.TradeSide;
import io.qiao.entity.Holding;
import io.qiao.entity.IdempotencyRecord;
import io.qiao.entity.Trade;
import io.qiao.exception.HoldingBusyException;
import io.qiao.exception.InvalidTradeException;
import io.qiao.exception.NotHeldException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.exception.TradeVersionConflictException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.quote.TwseQuoteClient;
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

	private static final Logger log = LoggerFactory.getLogger(TradeService.class);

	/** 每筆交易的股數上限（V-b：防打錯，一般投資人碰不到；1.0 的 1～999 張另外在 v1 檢查） */
	public static final long MAX_SHARES = 99_999_999L;
	/** 價格上限（和資料表 DECIMAL(10,2) 一致） */
	public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
	/**
	 * 單筆成交金額（股數 × 價格）上限 100 億元（朋友回饋 3，B-39 F3 a）。
	 * 為什麼：股數、價格各自的上限相乘約 10^16 元，多打幾個 0 也存得進去，App 1.0（v1）的金額會超過
	 * JSON 數字能精確表示的範圍（約 9×10^15）。100 億元一般投資人單筆不可能超過；就算累加 90 萬筆仍在安全範圍。
	 * 只限制 2.0 的寫入；App 1.0 的新增照 1.0 規格（1～999 張、買進價上限），不受影響。
	 */
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000000");
	/** 最早的交易日期（題目 1 a；資料庫也有同樣的 CHECK） */
	public static final LocalDate EARLIEST_DATE = LocalDate.of(1990, 1, 1);
	/** 買進最多試幾次（持股在交錯操作中被建立或刪除時重試；有上限，不會無限重試） */
	static final int MAX_BUY_ATTEMPTS = 3;

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final HoldingLock holdingLock;
	private final StockService stockService;
	private final StockLookupService stockLookupService;
	private final QuoteCache quoteCache;
	private final PositionCalculator positionCalculator;
	private final TransactionTemplate transactionTemplate;
	private final IdempotencyGuard idempotencyGuard;
	private final Clock clock;

	public TradeService(HoldingRepository holdingRepository, TradeRepository tradeRepository, HoldingLock holdingLock,
			StockService stockService, StockLookupService stockLookupService, QuoteCache quoteCache,
			PositionCalculator positionCalculator, TransactionTemplate transactionTemplate,
			IdempotencyGuard idempotencyGuard, Clock clock) {
		this.idempotencyGuard = idempotencyGuard;
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.holdingLock = holdingLock;
		this.stockService = stockService;
		this.stockLookupService = stockLookupService;
		this.quoteCache = quoteCache;
		this.positionCalculator = positionCalculator;
		this.transactionTemplate = readCommitted(transactionTemplate);
		this.clock = clock;
	}

	/**
	 * 2.0 寫入（買、賣、改、刪）的資料庫交易一律用 READ COMMITTED（2026-11-21 B-40 G1 a）。
	 *
	 * 為什麼：MySQL 預設的 REPEATABLE READ 會在交易裡「第一次一般查詢」時拍下快照，之後都讀那張快照。
	 * 佔 key 時先查 key 在不在（為了避開間隙鎖，朋友回饋 1）讓快照在「拿持股鎖之前」就拍下 →
	 * 排隊等鎖的請求拿到鎖後讀到舊的交易紀錄，看不到前一筆剛賣出的 → 同時賣出時賣超（真 MySQL 實測抓到）。
	 * READ COMMITTED 每一句都讀「最新已提交」的資料：拿到持股鎖之後讀的一定是最新的交易紀錄；
	 * 而且 MySQL 在這個等級下查詢、刪除不鎖間隙，不同 key 也不會再互相死結。
	 * 只限這裡的寫入交易；讀取（總覽、API 的 GET）仍是 REPEATABLE READ 的一致快照，數字彼此一致。
	 * PostgreSQL、Oracle、SQL Server 的預設都是 READ COMMITTED，「READ COMMITTED＋先鎖要改的列」是常見的寫入做法。
	 */
	private static TransactionTemplate readCommitted(TransactionTemplate shared) {
		TransactionTemplate template = new TransactionTemplate(shared.getTransactionManager());
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		return template;
	}

	// ===== 買進 =====

	/**
	 * 買進。還沒持有的代號會先向官方確認存在（最多 9 秒，在交易外，不佔資料庫連線），再建立持股；
	 * 已持有或已全部賣光的代號直接加一筆買進（不查官方）。
	 *
	 * 手機與電腦交錯操作時，持股可能「剛被建立」或「剛被刪除」（刪掉最後一筆交易會連持股一起刪），所以最多試 3 次：
	 * 1. 持股不存在 → 建立持股＋買進；撞到代號唯一 = 另一個裝置剛建立了（C3-4）→ 下一步
	 * 2. 持股存在 → 鎖住後加一筆買進；鎖住時持股已被另一個裝置刪除 → 回到 1
	 * 3 次都被打斷（極少見，代表兩邊不斷在建立、刪除同一檔）→ 409 請稍後再試，不會無限重試。
	 * 官方查詢整個流程最多只做一次（重試沿用同一份結果），回應時間才不會超過 App 的 10 秒。
	 *
	 * @throws io.qiao.exception.StockNotFoundException     上市、上櫃都查不到這個代號
	 * @throws io.qiao.exception.QuoteUnavailableException 官方連不上，無法確認代號
	 * @throws HoldingBusyException                        連續 3 次被其他裝置的建立、刪除打斷
	 */
	public TradeResult buy(String code, LocalDate tradeDate, long shares, BigDecimal price) {
		return buy(code, tradeDate, shares, price, null);
	}

	/**
	 * 買進（帶 Idempotency-Key，C7）。idempotency 為 null 時不做防重送（內部與測試使用）。
	 *
	 * @throws io.qiao.exception.IdempotencyKeyUsedException    這個 key 已經用過（API 層回傳那次的結果）
	 * @throws io.qiao.exception.IdempotencyInProgressException 同一個 key 的前一個請求還在處理
	 */
	public TradeResult buy(String code, LocalDate tradeDate, long shares, BigDecimal price,
			IdempotencyRequest idempotency) {
		String normalizedCode = StockCodes.normalize(code);
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);

		StockQuote quote = null; // 需要建立持股時才查官方，而且只查一次
		for (int attempt = 1; attempt <= MAX_BUY_ATTEMPTS; attempt++) {
			if (holdingRepository.findByCode(normalizedCode).isEmpty()) {
				if (quote == null) {
					quote = lookupForNewHolding(normalizedCode, tradeDate, now); // 交易外：查官方時不佔資料庫連線
				}
				Optional<TradeResult> created = createHoldingAndBuy(quote, tradeDate, shares, price, now, idempotency);
				if (created.isPresent()) {
					return recalculated(created.get());
				}
				// C3-4：撞到代號唯一 → 另一個裝置剛好同時建立了這檔 → 改成加到已存在的持股
			}
			try {
				return recalculated(inTransaction(() -> withIdempotency(idempotency, () -> addTrade(
						holdingLock.byCodeIfPresent(normalizedCode).orElseThrow(HoldingVanished::new),
						TradeSide.BUY, tradeDate, shares, price, now))));
			} catch (HoldingVanished e) {
				// 鎖住時持股已被另一個裝置刪除（刪掉最後一筆交易）→ 交易已復原（含 key），重新建立
			}
		}
		throw new HoldingBusyException(null);
	}

	/**
	 * 第一次買這檔：向官方確認代號存在（第 3 題：代號不存在不儲存）。
	 *
	 * 1. 先查近兩個月（和 1.0 相同）：查到 → 放進行情快取，回傳結果就有未實現損益。
	 * 2. 查不到，而且交易日期比上個月更早 → 改查「交易日期那個月」（第 6 題 L2 b：補登已下市股票的舊交易）。
	 *    那個月有成交 → 代號確實存在過，可以記錄；但那個月的收盤價**不放進行情快取**：
	 *    它不是現在的價格，放進去會被當成今天的收盤價算未實現損益。現在的狀態交給背景排程判斷（近期無成交資料）。
	 * 3. 都查不到 → 404，訊息寫出查過哪個月（2010 年以前的上市股票，證交所沒有資料，訊息會說明）。
	 * 交易日期在近兩個月內 → 步驟 1 已經查過那個月，不再多查。
	 */
	private StockQuote lookupForNewHolding(String code, LocalDate tradeDate, LocalDateTime now) {
		try {
			StockQuote quote = stockLookupService.lookup(code);
			quoteCache.putSuccess(quote, now);
			return quote;
		} catch (StockNotFoundException notInRecentMonths) {
			YearMonth tradeMonth = YearMonth.from(tradeDate);
			if (!tradeMonth.isBefore(YearMonth.now(clock).minusMonths(1))) {
				throw notInRecentMonths;
			}
			return stockLookupService.findInMonth(code, tradeMonth).orElseThrow(() -> StockNotFoundException
					.inMonth(code, tradeMonth, tradeMonth.isBefore(TwseQuoteClient.EARLIEST_MONTH)));
		}
	}

	/** 鎖住時持股已不存在：丟出來讓這個資料庫交易復原（連同佔住的 key），由 buy 重試 */
	private static final class HoldingVanished extends RuntimeException {
		HoldingVanished() {
			super(null, null, false, false); // 只用來控制流程，不需要堆疊資訊
		}
	}

	/**
	 * 建立持股＋第一筆買進（同一個交易）；代號已被另一個請求建立 → empty。
	 * 資料庫因為「代號重複」以外的原因拒絕（例如名稱太長）→ 照原本的錯誤丟出，不要誤當成「別人剛建立了」。
	 */
	private Optional<TradeResult> createHoldingAndBuy(StockQuote quote, LocalDate tradeDate, long shares,
			BigDecimal price, LocalDateTime now, IdempotencyRequest idempotency) {
		try {
			return Optional.of(inTransaction(() -> withIdempotency(idempotency, () -> {
				stockService.recordQuote(quote); // 先寫股票表（外鍵：持股的代號一定要在股票表裡）
				Holding holding = new Holding();
				holding.setCode(quote.code());
				holding.setCreatedAt(now);
				holding.setUpdatedAt(now);
				// saveAndFlush：立刻寫入，讓 UNIQUE 限制的錯誤在這裡發生
				return addTrade(holdingRepository.saveAndFlush(holding), TradeSide.BUY, tradeDate, shares, price, now);
			})));
		} catch (DataIntegrityViolationException e) {
			if (holdingRepository.findByCode(quote.code()).isPresent()) {
				return Optional.empty(); // 確實是別人剛建立了這檔
			}
			throw e;
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
		return sell(code, tradeDate, shares, price, null);
	}

	/** 賣出（帶 Idempotency-Key，C7）；idempotency 為 null 時不做防重送 */
	public TradeResult sell(String code, LocalDate tradeDate, long shares, BigDecimal price,
			IdempotencyRequest idempotency) {
		String normalizedCode = StockCodes.normalize(code);
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);
		return recalculated(inTransaction(() -> withIdempotency(idempotency,
				() -> addTrade(lockByCode(normalizedCode), TradeSide.SELL, tradeDate, shares, price, now))));
	}

	/** 在已鎖住的持股加一筆交易：先整條重算驗證（賣超就丟例外、不寫入），通過才寫入 */
	private TradeResult addTrade(Holding holding, TradeSide side, LocalDate tradeDate, long shares,
			BigDecimal price, LocalDateTime now) {
		List<TradeInput> inputs = new ArrayList<>(inputsOf(holding.getId()));
		inputs.add(new TradeInput(null, side, tradeDate, shares, price)); // 還沒存檔：排在同一天最後
		checkTimeline(holding, inputs); // 賣超 → OversellException，下面都不會執行

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
		return updateTrade(tradeId, expectedVersion, tradeDate, shares, price, null);
	}

	/** 修改一筆交易（帶 Idempotency-Key，C7）；idempotency 為 null 時不做防重送 */
	public TradeResult updateTrade(Long tradeId, Long expectedVersion, LocalDate tradeDate, long shares,
			BigDecimal price, IdempotencyRequest idempotency) {
		validate(tradeDate, shares, price);
		LocalDateTime now = LocalDateTime.now(clock);
		return recalculated(inTransaction(() -> withIdempotency(idempotency, () -> {
			Holding holding = lockHoldingOfTrade(tradeId);
			Trade trade = loadWithVersion(tradeId, expectedVersion);

			List<TradeInput> inputs = new ArrayList<>(inputsOf(holding.getId()).stream()
					.filter(input -> !input.id().equals(tradeId)).toList());
			inputs.add(new TradeInput(tradeId, trade.getSide(), tradeDate, shares, price));
			checkTimeline(holding, inputs); // 改完會賣超 → 例外，交易紀錄不變

			trade.setTradeDate(tradeDate);
			trade.setShares(shares);
			trade.setPrice(price);
			trade.setUpdatedAt(now);
			holding.setUpdatedAt(now);
			tradeRepository.flush(); // 立刻寫入：交易的版本號 +1，回傳的才是新版本
			return new TradeResult(holding, trade, null, false);
		})));
	}

	/**
	 * 刪除一筆交易。刪掉的是最後一筆 → 持股列也一起刪除（C2-1）。
	 *
	 * @throws TradeNotFoundException        找不到這筆交易
	 * @throws TradeVersionConflictException 版本號不符
	 * @throws io.qiao.calculation.OversellException 刪掉這筆買進後，之後的賣出變成賣超
	 */
	public TradeResult deleteTrade(Long tradeId, Long expectedVersion) {
		return deleteTrade(tradeId, expectedVersion, null);
	}

	/** 刪除一筆交易（帶 Idempotency-Key，C7）；idempotency 為 null 時不做防重送 */
	public TradeResult deleteTrade(Long tradeId, Long expectedVersion, IdempotencyRequest idempotency) {
		LocalDateTime now = LocalDateTime.now(clock);
		TradeResult result = inTransaction(() -> withIdempotency(idempotency, () -> {
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
			checkTimeline(holding, remaining); // 刪掉後會賣超 → 例外，什麼都不刪

			tradeRepository.delete(trade);
			holding.setUpdatedAt(now);
			return new TradeResult(holding, null, null, false);
		}));
		return result.holdingDeleted() ? result : recalculated(result);
	}

	/**
	 * 防止重送（C7-2 a）：在「目前這個資料庫交易」裡先佔住 key，再執行寫入，最後記下結果。
	 * key 與交易一起提交、一起復原：寫入失敗（例如賣超）→ key 不會留下，重送時重新執行。
	 * 先佔 key 再鎖持股：同一個 key 同時到達時，第二個在佔 key 這一步就排隊等待，不會進到寫入。
	 * 記下結果用 idempotencyGuard.complete（明確 save），原因見該方法說明。
	 */
	private TradeResult withIdempotency(IdempotencyRequest idempotency, Supplier<TradeResult> write) {
		if (idempotency == null) {
			return write.get();
		}
		IdempotencyRecord record = idempotencyGuard.claim(idempotency);
		TradeResult result = write.get();
		idempotencyGuard.complete(record, result.trade() != null ? result.trade().getId() : null,
				result.holding().getCode(), result.holdingDeleted());
		return result;
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
	 * | 金額 | 股數 × 價格最多 100 億元（防多打 0；App 1.0 金額不超出 JSON 精確範圍）      |
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
		if (price.multiply(BigDecimal.valueOf(shares)).compareTo(MAX_AMOUNT) > 0) {
			// 欄位標在 shares：多打 0 最常發生在股數；訊息請使用者兩個都確認
			throw new InvalidTradeException("shares", "單筆成交金額（股數 × 價格）最多 100 億元，請確認股數與價格是否多打了 0");
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
	/**
	 * 寫入前的驗證：依日期整條重算，任何時間點賣超就擋下（OversellException，什麼都不寫入）。
	 *
	 * 擋下時，如果這檔有「日期是推估的、而且晚於那筆賣出」的買進（1.0 轉入、App 1.0 新增時用記帳日代替），
	 * 很可能是推估日期太晚造成的 → 錯誤訊息提示使用者先修改那筆買進的日期，並附上 id、版本號（朋友回饋 2-1 a）。
	 * 不自動修改日期：系統不能替使用者猜真正的買進日（會讓平均成本、已實現損益算錯）。
	 */
	private void checkTimeline(Holding holding, List<TradeInput> inputs) {
		try {
			calculate(holding, inputs);
		} catch (OversellException e) {
			LocalDate oversoldDate = e.trade().tradeDate();
			List<OversellException.EstimatedBuy> estimated = tradeRepository
					.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId()).stream()
					.filter(trade -> trade.getSide() == TradeSide.BUY && trade.isDateEstimated()
							&& trade.getTradeDate().isAfter(oversoldDate))
					.map(trade -> new OversellException.EstimatedBuy(trade.getId(), trade.getTradeDate(), trade.getVersion()))
					.toList();
			throw e.withEstimatedBuys(estimated);
		}
	}

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
		try {
			PositionResult position = calculate(holding, inputsOf(holding.getId()));
			return new TradeResult(holding, written.trade(), position, false);
		} catch (OversellException e) {
			// 這裡是「已經提交之後」的重新計算：寫入已經成功，不能再回報失敗（否則用戶端以為沒寫入，資料其實已存在）。
			// 正常不會發生（寫入前已在持股鎖內整條驗證）；萬一發生（例如另一個裝置剛好改壞），照實記錄並回傳寫入結果，
			// 讀取時那一檔會標成「資料異常」（朋友回饋 1-1 a）
			log.error("寫入後重新計算失敗（寫入已提交）：代號 {}、持股 id {}、原因：{}", holding.getCode(), holding.getId(),
					e.getMessage());
			return new TradeResult(holding, written.trade(), null, false);
		}
	}

	private <T> T inTransaction(Supplier<T> work) {
		return transactionTemplate.execute(status -> work.get());
	}
}
