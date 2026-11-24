package io.qiao.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.qiao.entity.Market;
import io.qiao.quote.StockQuote;

/**
 * 真的 MySQL 上的等鎖逾時（Testcontainers 第 1 項，情境 #6）。
 *
 * 另一條連線用 SELECT … FOR UPDATE 鎖住 2330 不放（模擬另一個裝置很慢的請求），這時：
 * - App 2.0 賣出、App 1.0 修改都要在約 3 秒後回 409 HOLDING_BUSY（MySQL 預設要等 50 秒，比 App 的 10 秒重送還長）。
 * - 資料完全沒變；鎖放開後再送就成功。
 *
 * 考試時是手動在 MySQL 指令視窗鎖住、用 curl 送請求驗證（3.25 秒回 409），這裡變成自動測試。
 */
class MySqlLockTimeoutTest extends MySqlTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** 程式用的時鐘（台北時區，TimeConfig）：主機在夏威夷時，「今天」也要以台北為準 */
	@Autowired
	private Clock clock;

	private final ExecutorService threads = Executors.newSingleThreadExecutor();

	@BeforeEach
	void setUp() {
		cleanDatabase();
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(invocation.getArgument(0),
				"台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	@AfterEach
	void tearDown() {
		threads.shutdownNow();
		cleanDatabase();
	}

	@Test
	void 另一個裝置鎖住持股_v2賣出與v1修改約3秒後回409_資料不變_放開後再送成功() throws Exception {
		// App 1.0 新增一筆持股：1 張（1,000 股）
		String created = mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":600}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();

		// 另一個裝置：鎖住 2330 後卡住，直到測試放行
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> otherDevice = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.queryForList("SELECT id FROM holdings WHERE code = '2330' FOR UPDATE");
			locked.countDown();
			await(release);
		}));
		await(locked);

		String sellKey = UUID.randomUUID().toString();
		try {
			// App 2.0 賣出 → 等鎖約 3 秒後 409
			long startedAt = System.nanoTime();
			expectBusy(sellV2(sellKey));
			assertWaitedAboutThreeSeconds(startedAt);

			// App 1.0 修改 → 一樣約 3 秒後 409（錯誤格式和規格相同，App 1.0 會顯示 message）
			startedAt = System.nanoTime();
			expectBusy(updateV1(id));
			assertWaitedAboutThreeSeconds(startedAt);
		} finally {
			release.countDown(); // 測試失敗也要放開鎖，否則後面的測試會被卡住
			otherDevice.get(10, TimeUnit.SECONDS);
		}

		// 資料完全沒變：仍是 1 筆買進、1,000 股；逾時的賣出沒有記住 key（跟著交易一起復原）
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trades", Long.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Long.class)).isZero();
		mockMvc.perform(get("/api/v1/holdings/{id}", id)).andExpect(jsonPath("$.lots").value(1));
		mockMvc.perform(get("/api/v2/holdings/2330")).andExpect(jsonPath("$.shares").value(1000));

		// 鎖放開後再送：App 1.0 修改成 2 張 → 成功
		updateV1(id).andExpect(status().isOk()).andExpect(jsonPath("$.lots").value(2));
		// App 2.0 用同一個 key 重送賣出 → 真的執行（不是重送的結果），2,000 − 100 = 1,900 股
		sellV2(sellKey).andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
		mockMvc.perform(get("/api/v2/holdings/2330")).andExpect(jsonPath("$.shares").value(1900));
	}

	/**
	 * 賣出日期用「台北的今天」：App 1.0 新增的持股沒有買進日期，程式用新增當天當成推估的買進日期，
	 * 賣出日期比它早就是「買進前先賣出」（OVERSELL）；也不能是明天（未來的日期不能登記）。
	 */
	private ResultActions sellV2(String key) throws Exception {
		return mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"side\":\"SELL\",\"trade_date\":\"" + LocalDate.now(clock)
						+ "\",\"shares\":100,\"price\":\"650.00\"}"));
	}

	private ResultActions updateV1(long id) throws Exception {
		return mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":2,\"buy_price\":600}"));
	}

	private static void expectBusy(ResultActions actions) throws Exception {
		actions.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("HOLDING_BUSY"))
				.andExpect(jsonPath("$.error.message").value("資料正在更新，請稍後再試"));
	}

	/** 等鎖上限 3 秒：不能太快（代表沒有真的等鎖），也不能接近 App 的 10 秒重送 */
	private static void assertWaitedAboutThreeSeconds(long startedAt) {
		Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
		assertThat(waited).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(8));
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
