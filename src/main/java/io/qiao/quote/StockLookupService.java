package io.qiao.quote;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;

/**
 * 新增持股時用：使用者只輸入代號，系統自己判斷是上市還是上櫃，並取得名稱與最近收盤價。
 *
 * 做法：證交所、櫃買中心「同時」查（兩個執行緒），整體最多等 lookupTimeout（預設 9 秒）。
 * - 同時查的原因：依序查的話，最壞情況兩邊的逾時會相加，無法保證題目要求的「10 秒內看到結果」。
 * - 9 秒的原因：低於 10 秒；Part B 的 App 2.0 在 10 秒沒收到回應時會自動重送，也一併避開。
 *
 * 判斷規則：
 * | 證交所          | 櫃買中心        | 結果                       |
 * | 查到            | （不用等）      | 上市                       |
 * | 查無 / 連不上   | 查到            | 上櫃                       |
 * | 查無            | 查無            | StockNotFoundException     |
 * | 其他（有一邊連不上，且沒查到） |   | QuoteUnavailableException  |
 *
 * 這個類別只回傳行情（StockQuote），不直接修改持股，Part B 改資料模型時可以直接沿用。
 */
public class StockLookupService implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(StockLookupService.class);

	private final StockQuoteClient twseClient;
	private final StockQuoteClient tpexClient;
	private final Duration lookupTimeout;

	/**
	 * 自己管理的執行緒池：不用 Spring 共用的執行緒池，避免查官方時卡住其他功能。
	 * 每次查詢用 2 條（兩邊各一），8 條可同時處理 4 個人新增。
	 */
	private final ExecutorService executor = Executors.newFixedThreadPool(8);

	public StockLookupService(StockQuoteClient twseClient, StockQuoteClient tpexClient, Duration lookupTimeout) {
		this.twseClient = twseClient;
		this.tpexClient = tpexClient;
		this.lookupTimeout = lookupTimeout;
	}

	/**
	 * 查詢股票所屬市場、名稱與最近收盤價。
	 *
	 * @throws StockNotFoundException     上市、上櫃都確定查不到
	 * @throws QuoteUnavailableException 有一邊連不上（或超過時間），無法判斷
	 */
	public StockQuote lookup(String code) {
		long deadline = System.nanoTime() + lookupTimeout.toNanos();

		// 兩邊同時送出
		Future<Optional<StockQuote>> twseFuture = executor.submit(() -> twseClient.fetchLatest(code));
		Future<Optional<StockQuote>> tpexFuture = executor.submit(() -> tpexClient.fetchLatest(code));

		try {
			Answer twse = await(twseFuture, deadline, code);
			if (twse.quote().isPresent()) {
				return twse.quote().get();
			}

			Answer tpex = await(tpexFuture, deadline, code);
			if (tpex.quote().isPresent()) {
				return tpex.quote().get();
			}

			// 兩邊都「確定查無」才能說代號不存在
			if (twse.confirmedNotFound() && tpex.confirmedNotFound()) {
				throw new StockNotFoundException(code);
			}
			throw new QuoteUnavailableException("無法確認代號 " + code + " 是否存在（有來源連不上）");
		} finally {
			// 已經有結果就不用再等另一邊；還沒結束的請求直接中斷
			twseFuture.cancel(true);
			tpexFuture.cancel(true);
		}
	}

	/**
	 * 等某一邊的結果，最多等到 deadline。
	 * 連不上、逾時、超過整體時間，一律回傳「沒查到、也不確定查無」。
	 */
	private Answer await(Future<Optional<StockQuote>> future, long deadline, String code) {
		long remainingNanos = deadline - System.nanoTime();
		try {
			Optional<StockQuote> quote = future.get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
			return new Answer(quote, quote.isEmpty());
		} catch (TimeoutException e) {
			log.warn("查詢 {} 超過整體時間 {}", code, lookupTimeout);
			return Answer.UNKNOWN;
		} catch (ExecutionException e) {
			// fetchLatest 丟出的例外（通常是 QuoteUnavailableException）包在 ExecutionException 裡
			log.warn("查詢 {} 失敗：{}", code, e.getCause().getMessage());
			return Answer.UNKNOWN;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.UNKNOWN;
		}
	}

	/** 程式關閉時一併關掉執行緒池（Spring 會自動呼叫 close） */
	@Override
	public void close() {
		executor.shutdownNow();
	}

	/**
	 * 某一邊的查詢結果。
	 *
	 * @param quote             查到的行情
	 * @param confirmedNotFound true = 對方確實回覆「查無」；false = 連不上或逾時，無法確定
	 */
	private record Answer(Optional<StockQuote> quote, boolean confirmedNotFound) {
		static final Answer UNKNOWN = new Answer(Optional.empty(), false);
	}
}
