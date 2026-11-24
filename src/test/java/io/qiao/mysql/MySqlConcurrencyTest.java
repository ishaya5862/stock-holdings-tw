package io.qiao.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.qiao.entity.Market;
import io.qiao.quote.StockQuote;

/**
 * 真的 MySQL 上的同時操作（Testcontainers 第 1 項，情境 #2～#5）。
 *
 * 考試時兩個最嚴重的 bug 都只在 MySQL 出現，H2 測不出來：
 * - 不同的 Idempotency-Key 同時寫入 → 間隙鎖造成死結，被誤判成「同一個 key 還在處理」（朋友回饋 1）。
 * - 修正上一個問題時，交易的快照在拿持股鎖之前就拍下 → 同時賣出時看不到前一筆賣出 → 賣超（2.0 寫入改用 READ COMMITTED 修正）。
 *
 * 檢查方式：不只看狀態碼，還要檢查資料是否正確（交易筆數、剩下的股數）。
 * 同時操作的問題不一定每次都會發生，所以每個測試重複 3 輪（@RepeatedTest），和考試時手動驗證的輪數相同。
 *
 * 用 MockMvc 打 API（模擬 App 實際的用法），每個請求在自己的執行緒、各自開真的資料庫交易。
 */
class MySqlConcurrencyTest extends MySqlTestBase {

	private static final String TRADE_DATE = "2026-03-02";

	@Autowired
	private MockMvc mockMvc;

	private final ExecutorService threads = Executors.newFixedThreadPool(10);

	@BeforeEach
	void setUp() {
		cleanDatabase();
		// 假的官方查詢：任何代號都查得到（名稱、收盤價和測試無關）
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(invocation.getArgument(0),
				"測試股", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	@AfterEach
	void tearDown() {
		threads.shutdownNow();
		cleanDatabase();
	}

	/** 情境 #2：同一檔、同時 10 筆賣出 → 不可以賣超，賣出的總數和剩下的股數要對得上 */
	@RepeatedTest(3)
	void 同一檔持有1000股_同時10筆各賣200股_最多成功5筆_股數正確() throws Exception {
		assertThat(trade("2330", newKey(), buyJson(1000)).status).isEqualTo(201);

		List<Result> results = runAtTheSameTime(10, i -> trade("2330", newKey(), sellJson(200)));

		long succeeded = count(results, 201, null);
		long oversold = count(results, 409, "OVERSELL");
		long busy = count(results, 409, "HOLDING_BUSY"); // 等鎖超過 3 秒（10 筆都很快，正常不會發生，但不算錯）
		// 只能是這三種結果；不可以有 500，也不可以被誤判成「同一個 key 還在處理」
		assertThat(succeeded + oversold + busy).as("回應：%s", results).isEqualTo(10);
		// 拿到鎖的請求依序處理：前 5 筆成功，之後的都賣超
		assertThat(succeeded).isEqualTo(Math.min(5, 10 - busy));

		// 資料：交易筆數 = 1 筆買進 + 成功的賣出；剩下的股數 = 1000 − 實際賣出
		assertThat(tradeCount("2330")).isEqualTo(1 + succeeded);
		assertThat(shares("2330")).isEqualTo(1000 - 200 * succeeded);
	}

	/** 情境 #3：不同檔、不同 key 同時寫入 → 互不影響，全部成功 */
	@RepeatedTest(3)
	void 六檔不同的持股同時各買進一筆_全部成功_不會互相卡住() throws Exception {
		List<String> codes = List.of("2330", "0050", "2317", "2454", "6488", "00679B");
		for (String code : codes) {
			assertThat(trade(code, newKey(), buyJson(1000)).status).isEqualTo(201);
		}

		List<Result> results = runAtTheSameTime(codes.size(), i -> trade(codes.get(i), newKey(), buyJson(100)));

		// 不同的資源不需要等彼此，全部都要成功（考試時的 bug：死結被誤判成 409 IDEMPOTENCY_KEY_IN_PROGRESS）
		assertThat(results).as("回應：%s", results).allSatisfy(result -> assertThat(result.status).isEqualTo(201));
		for (String code : codes) {
			assertThat(tradeCount(code)).as(code).isEqualTo(2);
			assertThat(shares(code)).as(code).isEqualTo(1100);
		}
	}

	/** 情境 #4：同一檔、不同 key 同時買進（朋友在真 MySQL 抓到死結的情境） */
	@RepeatedTest(3)
	void 同一檔同時6筆買進_key都不同_每一筆都記錄_股數正確() throws Exception {
		assertThat(trade("2330", newKey(), buyJson(1000)).status).isEqualTo(201);

		List<Result> results = runAtTheSameTime(6, i -> trade("2330", newKey(), buyJson(100)));

		long succeeded = count(results, 201, null);
		long busy = count(results, 409, "HOLDING_BUSY");
		// 不可以有 500，也不可以被誤判成「同一個 key 還在處理」（每個 key 都不同）
		assertThat(succeeded + busy).as("回應：%s", results).isEqualTo(6);
		assertThat(succeeded).isPositive();

		assertThat(tradeCount("2330")).isEqualTo(1 + succeeded);
		assertThat(shares("2330")).isEqualTo(1000 + 100 * succeeded);
	}

	/** 情境 #5：同一個 key 同時送兩次（App 10 秒沒回應就重送）→ 只記一筆 */
	@RepeatedTest(3)
	void 同一個key同時送兩次_只記一筆_股數只加一次() throws Exception {
		assertThat(trade("2330", newKey(), buyJson(1000)).status).isEqualTo(201);
		String sameKey = newKey();

		List<Result> results = runAtTheSameTime(2, i -> trade("2330", sameKey, buyJson(300)));

		// 一個真的執行（201）；另一個等前一個完成後拿到同樣的結果（201，標示重送），
		// 或等太久回 409 IDEMPOTENCY_KEY_IN_PROGRESS（App 稍後用同一個 key 再送即可）
		assertThat(results).as("回應：%s", results).allSatisfy(result -> assertThat(result.status == 201
				|| (result.status == 409 && "IDEMPOTENCY_KEY_IN_PROGRESS".equals(result.errorCode))).isTrue());
		assertThat(count(results, 201, null)).isPositive();
		long executed = results.stream().filter(r -> r.status == 201 && !r.replayed).count();
		assertThat(executed).as("真的執行的只能有一個：%s", results).isEqualTo(1);

		assertThat(tradeCount("2330")).isEqualTo(2);
		assertThat(shares("2330")).isEqualTo(1300);
	}

	// ===== 工具 =====

	/** 一個 API 請求的結果（狀態碼、錯誤代碼、是不是重送） */
	private record Result(int status, String errorCode, boolean replayed) {
	}

	@FunctionalInterface
	private interface Request {
		Result send(int index) throws Exception;
	}

	/** 同時送出 n 個請求：全部準備好才一起開始（CountDownLatch：倒數到 0 時，等待中的執行緒同時放行） */
	private List<Result> runAtTheSameTime(int n, Request request) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Result>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int index = i;
			Callable<Result> task = () -> {
				start.await();
				return request.send(index);
			};
			futures.add(threads.submit(task));
		}
		start.countDown();
		List<Result> results = new ArrayList<>();
		for (Future<Result> future : futures) {
			results.add(future.get(30, TimeUnit.SECONDS));
		}
		return results;
	}

	private Result trade(String code, String key, String json) throws Exception {
		var response = mockMvc.perform(post("/api/v2/holdings/{code}/trades", code)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
				.andReturn().getResponse();
		String errorCode = response.getStatus() >= 400
				? JsonPath.read(response.getContentAsString(), "$.error.code")
				: null;
		return new Result(response.getStatus(), errorCode, "true".equals(response.getHeader("Idempotent-Replayed")));
	}

	/** 目前持有的股數：用 API 查（和 App 看到的一樣，是用交易紀錄重算出來的） */
	private long shares(String code) throws Exception {
		String json = mockMvc.perform(get("/api/v2/holdings/{code}", code)).andReturn().getResponse().getContentAsString();
		Number shares = JsonPath.read(json, "$.shares"); // 資料異常（例如賣超）時是 null → 這裡會失敗
		return shares.longValue();
	}

	/** 資料庫裡這一檔的交易筆數（直接查資料表，不經過程式的計算） */
	private long tradeCount(String code) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM trades t JOIN holdings h ON h.id = t.holding_id WHERE h.code = ?", Long.class, code);
	}

	private static long count(List<Result> results, int status, String errorCode) {
		return results.stream()
				.filter(r -> r.status == status && (errorCode == null || errorCode.equals(r.errorCode)))
				.count();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	private static String buyJson(long shares) {
		return tradeJson("BUY", shares);
	}

	private static String sellJson(long shares) {
		return tradeJson("SELL", shares);
	}

	private static String tradeJson(String side, long shares) {
		return "{\"side\":\"" + side + "\",\"trade_date\":\"" + TRADE_DATE + "\",\"shares\":" + shares
				+ ",\"price\":\"600.00\"}";
	}
}
