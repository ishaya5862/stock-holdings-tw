package io.qiao.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import io.qiao.entity.IdempotencyRecord;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.IdempotencyRecordRepository;
import io.qiao.repository.TradeRepository;
import io.qiao.service.IdempotencyGuard;
import io.qiao.service.TradeService;

/**
 * 防止重送（冪等）測試（第 6 題 C7）：App 2.0 10 秒沒收到回應會自動重送同一個請求，同一筆不能記兩次。
 *
 * 決定（PARTB_NOTES B-27）：C7-1 b 一律必帶、C7-2 a key 和交易同一個資料庫交易、保存 24 小時、
 * 同 key 不同內容 422、指紋用解析後的欄位。兩個相同請求「同時」到達另外在 IdempotencyConcurrencyTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiV2IdempotencyTest {

	private static final String KEY_HEADER = "Idempotency-Key";
	private static final String BUY_300 = "{\"side\":\"BUY\",\"trade_date\":\"2026-03-02\",\"shares\":300,\"price\":\"600.00\"}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private IdempotencyRecordRepository idempotencyRepository;

	@Autowired
	private IdempotencyGuard idempotencyGuard;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	private ResultActions postTrade(String key, String json) throws Exception {
		return mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330").header(KEY_HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static long tradeIdOf(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.trade.id")).longValue();
	}

	// ===== 必帶、格式 =====

	@Test
	void 沒帶key_400_IDEMPOTENCY_KEY_REQUIRED_買賣修改刪除都一樣() throws Exception {
		mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330").contentType(MediaType.APPLICATION_JSON).content(BUY_300))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		mockMvc.perform(put("/api/v2/trades/{id}", 1).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\",\"version\":0}"))
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		mockMvc.perform(delete("/api/v2/trades/{id}", 1).param("version", "0"))
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		assertThat(tradeRepository.count()).isZero();
	}

	@Test
	void key格式不對_400_接受草案的雙引號寫法() throws Exception {
		postTrade("有中文", BUY_300).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_INVALID"));
		postTrade("a".repeat(65), BUY_300).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_INVALID"));

		postTrade("\"8e03978e-40d5-43e8-bc93-6894a57f9324\"", BUY_300).andExpect(status().isCreated());
	}

	// ===== 重送 =====

	@Test
	void 同一個key重送_只記一筆_回傳同一筆交易並標示重送() throws Exception {
		String key = UUID.randomUUID().toString();

		MvcResult first = postTrade(key, BUY_300)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andReturn();
		MvcResult retry = postTrade(key, BUY_300)
				.andExpect(status().isCreated()) // 和第一次相同的狀態碼
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(header().string("Location", first.getResponse().getHeader("Location")))
				.andExpect(jsonPath("$.holding.shares").value(300))
				.andReturn();

		assertThat(tradeIdOf(retry)).isEqualTo(tradeIdOf(first));
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	@Test
	void 價格寫法不同但數值相同_視為同一個請求() throws Exception {
		String key = UUID.randomUUID().toString();
		postTrade(key, BUY_300).andExpect(status().isCreated());

		// "600" 和 "600.00"、欄位順序不同、多了空白 → 指紋用解析後的欄位，仍是同一個請求
		postTrade(key, "{ \"price\": \"600\", \"shares\": 300, \"trade_date\": \"2026-03-02\", \"side\": \"BUY\" }")
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"));
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	@Test
	void 同一個key送不同內容或不同API_422_不執行() throws Exception {
		String key = UUID.randomUUID().toString();
		MvcResult first = postTrade(key, BUY_300).andExpect(status().isCreated()).andReturn();

		postTrade(key, BUY_300.replace("300", "500"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
		mockMvc.perform(put("/api/v2/trades/{id}", tradeIdOf(first)).header(KEY_HEADER, key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\",\"version\":0}"))
				.andExpect(status().isUnprocessableEntity());
		assertThat(tradeRepository.count()).isEqualTo(1);
		assertThat(tradeRepository.findById(tradeIdOf(first)).orElseThrow().getShares()).isEqualTo(300L);
	}

	// 「第一次失敗不記住」要真的復原資料庫交易，放在 IdempotencyConcurrencyTest（不加 @Transactional）

	@Test
	void 修改重送_版本號只加一次_不會變成版本衝突() throws Exception {
		Trade trade = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();
		long versionBefore = trade.getVersion();
		String key = UUID.randomUUID().toString();
		String body = "{\"trade_date\":\"2026-03-02\",\"shares\":500,\"price\":\"600\",\"version\":" + versionBefore + "}";

		mockMvc.perform(put("/api/v2/trades/{id}", trade.getId()).header(KEY_HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trade.version").value(versionBefore + 1));
		// 沒有冪等的話，重送會用舊版本號 → 409 VERSION_CONFLICT，使用者以為失敗了
		mockMvc.perform(put("/api/v2/trades/{id}", trade.getId()).header(KEY_HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.trade.version").value(versionBefore + 1));
	}

	@Test
	void 刪除重送_不會變成找不到() throws Exception {
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00"));
		Trade second = tradeService.buy("2330", LocalDate.of(2026, 5, 15), 500, new BigDecimal("620.00")).trade();
		String key = UUID.randomUUID().toString();

		mockMvc.perform(delete("/api/v2/trades/{id}", second.getId()).header(KEY_HEADER, key)
				.param("version", second.getVersion().toString()))
				.andExpect(status().isOk());
		// 沒有冪等的話，重送會得到 404 TRADE_NOT_FOUND
		mockMvc.perform(delete("/api/v2/trades/{id}", second.getId()).header(KEY_HEADER, key)
				.param("version", second.getVersion().toString()))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.holding.shares").value(300));
	}

	@Test
	void 刪除最後一筆的重送_一樣回報持股已刪除() throws Exception {
		Trade only = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();
		String key = UUID.randomUUID().toString();

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(delete("/api/v2/trades/{id}", only.getId()).header(KEY_HEADER, key)
					.param("version", only.getVersion().toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.holding_deleted").value(true))
					.andExpect(jsonPath("$.holding").value(Matchers.nullValue()));
		}
	}

	@Test
	void 重送時那筆交易已被另一個裝置刪除_回傳目前狀態而不是錯誤() throws Exception {
		String key = UUID.randomUUID().toString();
		MvcResult first = postTrade(key, BUY_300).andExpect(status().isCreated()).andReturn();
		Trade trade = tradeRepository.findById(tradeIdOf(first)).orElseThrow();
		tradeService.deleteTrade(trade.getId(), trade.getVersion()); // 另一個裝置刪掉了（最後一筆 → 持股也刪）

		postTrade(key, BUY_300)
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.trade").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.holding").value(Matchers.nullValue()));
		assertThat(tradeRepository.count()).isZero(); // 沒有因為重送又記一筆
	}

	// ===== 有效期限 =====

	@Test
	void 超過24小時的同一個key_當成新的請求() throws Exception {
		String key = UUID.randomUUID().toString();
		idempotencyRepository.saveAndFlush(new IdempotencyRecord(key, "0".repeat(64), "POST", "/old", 201,
				LocalDateTime.now(clock).minusHours(25)));

		postTrade(key, BUY_300)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	@Test
	void 每小時清除_只刪過期的key() {
		LocalDateTime now = LocalDateTime.now(clock);
		idempotencyRepository.saveAndFlush(new IdempotencyRecord("old", "0".repeat(64), "POST", "/old", 201, now.minusHours(25)));
		idempotencyRepository.saveAndFlush(new IdempotencyRecord("new", "0".repeat(64), "POST", "/new", 201, now.minusHours(1)));

		idempotencyGuard.purgeExpired();

		assertThat(idempotencyRepository.findAll()).extracting(IdempotencyRecord::getId).containsExactly("new");
	}

	// ===== v1 不受影響 =====

	@Test
	void App1點0的v1不需要key() throws Exception {
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":600}"))
				.andExpect(status().isCreated());
	}
}
