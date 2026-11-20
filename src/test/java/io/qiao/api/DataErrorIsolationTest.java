package io.qiao.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.TradeSide;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.TradeRepository;
import io.qiao.service.HoldingService;
import io.qiao.service.TradeService;

/**
 * 朋友測試回饋的問題 1、2（2026-11-21）：
 *
 * 1. 一檔的交易時間線不合理（正常操作不會發生，例如直接改了資料庫）→ 原本 v1 清單、v1／v2 總計、v2 單筆、
 *    v2 交易明細全部 500。改成故障隔離（1-1 a）：只有那一檔標成「資料異常」，其他照常；v1 不輸出那一檔（1-2 a）；
 *    v2 交易明細照樣列出原始交易，使用者才能修正。
 * 2. 推估日期（1.0 轉入、App 1.0 新增用記帳日代替）擋住補登的賣出 → 賣超訊息提示並附上那筆買進（2-1 a）。
 *
 * 不合理的資料直接用 TradeRepository 寫入（模擬直接改資料庫），和朋友的測試方式相同。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DataErrorIsolationTest {

	/** v2 寫入一律要帶 Idempotency-Key（C7-1 b）；每個不同的操作用新的 key */
	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	private Long brokenHoldingId;
	private Trade brokenBuy;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "測試股", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));

		// 正常的持股：2330 買 300 股 @600 → 成本 180,256
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00"));

		// 異常的持股（朋友的情境）：2363 由 App 1.0 新增（買進日期 = 今天、推估），
		// 再直接寫入一筆「日期早於買進」的賣出 1,000 股 → 賣出當時 0 股
		brokenHoldingId = holdingService.create("2363", 1, new BigDecimal("10")).getId();
		brokenBuy = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(brokenHoldingId).get(0);
		tradeRepository.save(Trade.create(brokenHoldingId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock)));
	}

	// ===== 1. 故障隔離 =====

	@Test
	void v2清單_異常的那一檔標出原因_其他照常() throws Exception {
		mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.code == '2330')].shares").value(300))
				.andExpect(jsonPath("$[?(@.code == '2330')].data_error").value(Matchers.contains(Matchers.nullValue())))
				.andExpect(jsonPath("$[?(@.code == '2363')].data_error")
						.value(Matchers.contains("2026-03-02 賣出 1000 股，但當時只持有 0 股")))
				.andExpect(jsonPath("$[?(@.code == '2363')].shares").value(Matchers.contains(Matchers.nullValue())));
	}

	@Test
	void v2總計_不算異常的那一檔_另外計數() throws Exception {
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value("180256.00"))
				.andExpect(jsonPath("$.holding_count").value(1))
				.andExpect(jsonPath("$.data_error_count").value(1));
	}

	@Test
	void v2單筆與交易明細_照樣看得到_明細列出原始交易讓使用者修正() throws Exception {
		mockMvc.perform(get("/api/v2/holdings/{code}", "2363"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data_error").value(Matchers.containsString("只持有 0 股")))
				.andExpect(jsonPath("$.total_cost").value(Matchers.nullValue()));
		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "2363"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].side").value("SELL"))
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].version").isNumber())
				.andExpect(jsonPath("$[0].shares").value(1000))
				.andExpect(jsonPath("$[0].fee").value(Matchers.nullValue())) // 算不出來的欄位是 null
				.andExpect(jsonPath("$[1].date_estimated").value(true));
	}

	@Test
	void v1清單與總計_不輸出異常的那一檔_其他照常_單筆404() throws Exception {
		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].code").value("2330"));
		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(180256));
		mockMvc.perform(get("/api/v1/holdings/{id}", brokenHoldingId))
				.andExpect(status().isNotFound());
	}

	@Test
	void v1新增異常的那一檔_409請用2點0處理() throws Exception {
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2363\",\"lots\":1,\"buy_price\":10}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("UPGRADE_REQUIRED"));
	}

	@Test
	void 第一版網頁_異常的那一檔顯示原因_其他照常() throws Exception {
		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("資料異常，暫時無法計算")))
				.andExpect(content().string(Matchers.containsString("2026-03-02 賣出 1000 股，但當時只持有 0 股")))
				.andExpect(content().string(Matchers.containsString("2330")));
	}

	@Test
	void 使用者用2點0把推估的買進日期改早_資料恢復正常() throws Exception {
		mockMvc.perform(put("/api/v2/trades/{id}", brokenBuy.getId()).header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-01-05\",\"shares\":1000,\"price\":\"10.00\",\"version\":"
						+ brokenBuy.getVersion() + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trade.date_estimated").value(false));

		mockMvc.perform(get("/api/v2/holdings/{code}", "2363"))
				.andExpect(jsonPath("$.data_error").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.shares").value(0)); // 1 月買 1,000、3 月賣 1,000 → 全部賣光
	}

	// ===== 2. 推估日期擋住補登的賣出：提示並附上那筆買進 =====

	@Test
	void 補登比推估日期更早的賣出_賣超訊息提示推估日期_附上那筆買進() throws Exception {
		Long holdingId = holdingService.create("2317", 2, new BigDecimal("150")).getId(); // App 1.0 新增：推估日期 = 今天
		Trade estimated = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId).get(0);
		LocalDate today = LocalDate.now(clock);

		mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2317").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"side\":\"SELL\",\"trade_date\":\"2026-03-02\",\"shares\":1000,\"price\":\"160.00\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("OVERSELL"))
				.andExpect(jsonPath("$.error.message").value("2026-03-02 賣出 1000 股，但當時只持有 0 股。"
						+ "這檔有 1 筆買進的日期是推估的（1.0 沒有買進日期，用記帳日 " + today
						+ " 代替），如果實際買進日期比較早，請先修改那筆買進的日期"))
				.andExpect(jsonPath("$.error.details.estimated_buys.length()").value(1))
				.andExpect(jsonPath("$.error.details.estimated_buys[0].id").value(estimated.getId().intValue()))
				.andExpect(jsonPath("$.error.details.estimated_buys[0].trade_date").value(today.toString()))
				.andExpect(jsonPath("$.error.details.estimated_buys[0].version").value(estimated.getVersion().intValue()));
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId)).hasSize(1); // 沒有寫入
	}

	@Test
	void 賣超但沒有推估日期的買進_訊息不加提示() throws Exception {
		mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"side\":\"SELL\",\"trade_date\":\"2026-05-15\",\"shares\":301,\"price\":\"650.00\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.message").value("2026-05-15 賣出 301 股，但當時只持有 300 股"))
				.andExpect(jsonPath("$.error.details.estimated_buys").doesNotExist());
	}
}
