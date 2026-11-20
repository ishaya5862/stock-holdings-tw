package io.qiao.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.qiao.entity.IdempotencyRecord;
import io.qiao.entity.Market;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.IdempotencyRecordRepository;
import io.qiao.repository.StockRepository;
import io.qiao.repository.TradeRepository;

/**
 * 防止重送（第 6 題 C7）中「需要真的提交／復原資料庫交易」的情境：
 * - 兩個相同 Idempotency-Key 的請求「同時」到達（朋友測試回饋建議要測）。
 *   情境：第一次還在查官方（最多 9 秒），App 10 秒逾時就重送 → 兩個請求同時在跑。
 * - 第一次失敗 → key 跟著交易一起復原，重送時重新執行。
 * - 提交後 key 真的記下寫了哪一筆（曾經的 bug：建立新持股時記錄行情會清空 JPA 暫存區，結果沒寫進去）。
 *
 * 不加 @Transactional：每個請求要各自開真的資料庫交易（和正式環境一樣），才測得到提交與復原；前後自己清資料。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IdempotencyRecordRepository idempotencyRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private StockRepository stockRepository;

	@MockitoBean
	private StockLookupService stockLookupService;

	private static final String BUY_300 = "{\"side\":\"BUY\",\"trade_date\":\"2026-03-02\",\"shares\":300,\"price\":\"600.00\"}";

	private final ExecutorService threads = Executors.newFixedThreadPool(2);

	@BeforeEach
	void setUp() {
		clean();
		// 查官方要一點時間：讓兩個請求真的重疊
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> {
			Thread.sleep(300);
			return new StockQuote(invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"),
					LocalDate.of(2026, 11, 20));
		});
	}

	@AfterEach
	void tearDown() {
		threads.shutdownNow();
		clean();
	}

	@Test
	void 相同key的兩個請求同時到達_只記一筆() throws Exception {
		String key = UUID.randomUUID().toString();
		CountDownLatch start = new CountDownLatch(1);
		Callable<MockHttpServletResponse> sameRequest = () -> {
			start.await(); // 兩個執行緒都準備好才一起送出
			return send(key, BUY_300).andReturn().getResponse();
		};

		Future<MockHttpServletResponse> first = threads.submit(sameRequest);
		Future<MockHttpServletResponse> retry = threads.submit(sameRequest);
		start.countDown();
		List<MockHttpServletResponse> responses = List.of(first.get(15, TimeUnit.SECONDS), retry.get(15, TimeUnit.SECONDS));

		// 只記一筆（最重要）；兩個都是 201（後到的等前一個完成後拿到結果，標示重送），
		// 或後到的等太久回 409 IDEMPOTENCY_KEY_IN_PROGRESS（App 稍後用同一個 key 再送即可）
		assertThat(tradeRepository.count()).isEqualTo(1);
		assertThat(responses).extracting(MockHttpServletResponse::getStatus).contains(201)
				.allSatisfy(statusCode -> assertThat(statusCode).isIn(201, 409));
		long replayed = responses.stream().filter(r -> "true".equals(r.getHeader("Idempotent-Replayed"))).count();
		long created = responses.stream().filter(r -> r.getStatus() == 201).count();
		assertThat(replayed).isEqualTo(created - 1); // 只有一個是真的執行，其他 201 都是重送
	}

	@Test
	void 第一次失敗不記住_重送時重新執行() throws Exception {
		String key = UUID.randomUUID().toString();
		String sell = "{\"side\":\"SELL\",\"trade_date\":\"2026-05-15\",\"shares\":200,\"price\":\"650.00\"}";

		send(key, sell).andExpect(status().isNotFound()); // 還沒持有 → 404，key 跟著復原
		assertThat(idempotencyRepository.count()).isZero();
		send(UUID.randomUUID().toString(), BUY_300).andExpect(status().isCreated());

		send(key, sell)
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed")); // 真的執行了
		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	@Test
	void 建立新持股的買進_提交後key記下寫了哪一筆_重送回同一筆() throws Exception {
		String key = UUID.randomUUID().toString();
		MvcResult first = send(key, BUY_300).andExpect(status().isCreated()).andReturn();
		long tradeId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.trade.id")).longValue();

		IdempotencyRecord saved = idempotencyRepository.findById(key).orElseThrow();
		assertThat(saved.getTradeId()).isEqualTo(tradeId);
		assertThat(saved.getHoldingCode()).isEqualTo("2330");

		send(key, BUY_300)
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(header().string("Location", "/api/v2/trades/" + tradeId))
				.andExpect(jsonPath("$.trade.id").value(tradeId));
	}

	private ResultActions send(String key, String json) throws Exception {
		return mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private void clean() {
		idempotencyRepository.deleteAll();
		tradeRepository.deleteAll();
		holdingRepository.deleteAll();
		stockRepository.deleteAll();
	}
}
