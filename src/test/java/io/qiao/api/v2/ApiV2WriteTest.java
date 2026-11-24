package io.qiao.api.v2;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.TradeService;

/**
 * App 2.0 寫入 API 測試（第 6 題 C6）：買進、賣出、修改、刪除交易；欄位錯誤、賣超、版本衝突的回應格式。
 *
 * 決定（PARTB_NOTES B-24）：C6-1 POST /holdings/{code}/trades、C6-2 版本號在內容／網址參數＋409、
 * C6-3 回交易＋重算持股、C6-4 fields 物件、C6-5 賣超 409 OVERSELL＋details、C6-6 價格用字串、C6-7 PUT 全部欄位。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiV2WriteTest {

	/** v2 寫入一律要帶 Idempotency-Key（C7-1 b）；每個不同的操作用新的 key */
	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	private ResultActions postTrade(String code, String json) throws Exception {
		return mockMvc.perform(post("/api/v2/holdings/{code}/trades", code).header("Idempotency-Key", newKey())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String trade(String side, String date, String shares, String price) {
		return "{\"side\":\"" + side + "\",\"trade_date\":\"" + date + "\",\"shares\":" + shares + ",\"price\":" + price + "}";
	}

	// ===== 買進、賣出 =====

	@Test
	void 買進還沒持有的代號_201_回傳交易與重算後的持股() throws Exception {
		postTrade("2330", trade("BUY", "2026-03-02", "300", "\"600.00\""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", Matchers.startsWith("/api/v2/trades/")))
				.andExpect(jsonPath("$.trade.side").value("BUY"))
				.andExpect(jsonPath("$.trade.shares").value(300))
				.andExpect(jsonPath("$.trade.price").value("600.00"))
				.andExpect(jsonPath("$.trade.fee").value("256.00"))
				.andExpect(jsonPath("$.trade.date_estimated").value(false))
				.andExpect(jsonPath("$.holding.code").value("2330"))
				.andExpect(jsonPath("$.holding.shares").value(300))
				.andExpect(jsonPath("$.holding.total_cost").value("180256.00"))
				.andExpect(jsonPath("$.holding_deleted").value(false));
	}

	@Test
	void 賣出一部分_回傳這次的已實現損益與剩下的持股() throws Exception {
		postTrade("2330", trade("BUY", "2026-03-02", "300", "\"600.00\""));
		postTrade("2330", trade("BUY", "2026-05-15", "500", "\"620.00\""));

		postTrade("2330", trade("SELL", "2026-08-20", "200", "\"700.00\""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.trade.side").value("SELL"))
				.andExpect(jsonPath("$.trade.tax").value("420.00"))
				.andExpect(jsonPath("$.trade.realized_pnl").value("16706.75"))
				.andExpect(jsonPath("$.trade.realized_return_pct").value("13.62")) // 16,706.75 ÷ 122,674.25 × 100
				.andExpect(jsonPath("$.holding.realized_return_pct").value("13.62"))
				.andExpect(jsonPath("$.holding.shares").value(600))
				.andExpect(jsonPath("$.holding.average_cost").value("613.37"))
				.andExpect(jsonPath("$.holding.unrealized_pnl").value("20252.25"));
	}

	@Test
	void 週末或休市日_伺服器照樣接受() throws Exception {
		postTrade("2330", trade("BUY", "2026-11-14", "100", "\"600\"")) // 星期六
				.andExpect(status().isCreated());
	}

	@Test
	void 代號全形小寫也能買() throws Exception {
		postTrade("２３３０", trade("BUY", "2026-03-02", "100", "\"600\""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.holding.code").value("2330"));
	}

	// ===== 錯誤 =====

	@Test
	void 多個欄位格式錯_一次全部標出來_400() throws Exception {
		postTrade("2330", "{\"side\":\"HOLD\",\"trade_date\":\"2026/11/20\",\"shares\":\"300\",\"price\":600.5}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.fields.side").value("買賣種類必須是 BUY 或 SELL"))
				.andExpect(jsonPath("$.error.fields.trade_date").value("交易日期必須是有效的 YYYY-MM-DD，例如 2026-11-20"))
				.andExpect(jsonPath("$.error.fields.shares").value("股數必須是整數（例如 300、1500）"))
				.andExpect(jsonPath("$.error.fields.price").value("價格請用字串，例如 \"600.50\"（避免小數誤差）"))
				.andExpect(jsonPath("$.error.message").value(Matchers.containsString("買賣種類必須是 BUY 或 SELL")));
	}

	@Test
	void 沒有內容或缺欄位_400() throws Exception {
		mockMvc.perform(post("/api/v2/holdings/{code}/trades", "2330").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.body").exists());
		postTrade("2330", "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.side").value("請輸入買賣種類（BUY 或 SELL）"))
				.andExpect(jsonPath("$.error.fields.price").value("請輸入價格，例如 \"600.50\""));
	}

	@Test
	void 範圍錯誤_股數超過上限說明分批_日期晚於台北今天() throws Exception {
		postTrade("2330", trade("BUY", "2026-03-02", "100000000", "\"600\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.shares").value("單筆最多 99,999,999 股；數量更大請分成多筆記錄"));

		// 朋友回饋 3：股數、價格各自合格，但相乘超過 100 億 → 400，標在 shares
		postTrade("2330", trade("BUY", "2026-03-02", "99999999", "\"99999999.99\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.fields.shares").value(Matchers.startsWith("單筆成交金額（股數 × 價格）最多 100 億元")));

		LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
		postTrade("2330", trade("BUY", tomorrow.toString(), "100", "\"600\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.trade_date").value(Matchers.startsWith("交易日期不能晚於今天")));
	}

	@Test
	void 賣超_409_OVERSELL_附哪一天與當時持有多少() throws Exception {
		postTrade("2330", trade("BUY", "2026-03-02", "300", "\"600.00\""));

		postTrade("2330", trade("SELL", "2026-05-15", "301", "\"650.00\""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("OVERSELL"))
				.andExpect(jsonPath("$.error.message").value("2026-05-15 賣出 301 股，但當時只持有 300 股"))
				.andExpect(jsonPath("$.error.details.trade_date").value("2026-05-15"))
				.andExpect(jsonPath("$.error.details.held_before").value(300));
	}

	@Test
	void 賣出沒有持有的代號_404_NOT_HELD() throws Exception {
		postTrade("2330", trade("SELL", "2026-03-02", "100", "\"600\""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_HELD"));
	}

	@Test
	void 買進格式不可能存在的代號_404_不浪費官方查詢() throws Exception {
		postTrade("ABC", trade("BUY", "2026-03-02", "100", "\"600\""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
		verify(stockLookupService, never()).lookup(anyString());
	}

	// ===== 修改 =====

	@Test
	void 修改_200_版本號加一_改了日期就不再推估() throws Exception {
		Trade trade = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();
		// 先存起來：測試和伺服器在同一個測試交易裡，修改後 trade 這個物件的版本號也會跟著變
		long versionBefore = trade.getVersion();

		mockMvc.perform(put("/api/v2/trades/{id}", trade.getId()).header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-03\",\"shares\":500,\"price\":\"590.50\",\"version\":" + versionBefore + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trade.trade_date").value("2026-03-03"))
				.andExpect(jsonPath("$.trade.shares").value(500))
				.andExpect(jsonPath("$.trade.price").value("590.50"))
				.andExpect(jsonPath("$.trade.version").value(versionBefore + 1))
				.andExpect(jsonPath("$.holding.shares").value(500));
	}

	@Test
	void 修改_版本號不符_409_VERSION_CONFLICT() throws Exception {
		Trade trade = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();

		mockMvc.perform(put("/api/v2/trades/{id}", trade.getId()).header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\",\"version\":" + (trade.getVersion() + 5) + "}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.error.message").value("這筆交易已在另一個裝置修改過，請重新整理後再操作"));
	}

	@Test
	void 修改_帶了買賣種類或代號_沒帶版本號_400() throws Exception {
		Trade trade = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();

		mockMvc.perform(put("/api/v2/trades/{id}", trade.getId()).header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"side\":\"SELL\",\"code\":\"2317\",\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.side").value("買賣種類不能修改；要改請刪除這筆後重新記錄"))
				.andExpect(jsonPath("$.error.fields.code").value("股票代號不能修改；要改請刪除這筆後重新記錄"))
				.andExpect(jsonPath("$.error.fields.version").value("請帶上版本號 version（讀取交易時拿到的值）"));
	}

	@Test
	void 修改造成之後賣超_409_OVERSELL() throws Exception {
		Trade buy = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 500, new BigDecimal("600.00")).trade();
		tradeService.sell("2330", LocalDate.of(2026, 5, 15), 300, new BigDecimal("650.00"));

		mockMvc.perform(put("/api/v2/trades/{id}", buy.getId()).header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\",\"version\":" + buy.getVersion() + "}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("OVERSELL"))
				.andExpect(jsonPath("$.error.details.held_before").value(100));
	}

	// ===== 刪除 =====

	@Test
	void 刪除一筆_200_回傳重算後的持股() throws Exception {
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00"));
		Trade second = tradeService.buy("2330", LocalDate.of(2026, 5, 15), 500, new BigDecimal("620.00")).trade();

		mockMvc.perform(delete("/api/v2/trades/{id}", second.getId()).header("Idempotency-Key", newKey()).param("version", second.getVersion().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.trade").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.holding.shares").value(300))
				.andExpect(jsonPath("$.holding_deleted").value(false));
	}

	@Test
	void 刪除最後一筆_持股一起刪除_holding是null() throws Exception {
		Trade only = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();

		mockMvc.perform(delete("/api/v2/trades/{id}", only.getId()).header("Idempotency-Key", newKey()).param("version", only.getVersion().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.holding").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.holding_deleted").value(true));
		mockMvc.perform(get("/api/v2/holdings/{code}", "2330")).andExpect(status().isNotFound());
	}

	@Test
	void 刪除_沒帶版本號或不是整數_400() throws Exception {
		Trade only = tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")).trade();

		mockMvc.perform(delete("/api/v2/trades/{id}", only.getId()).header("Idempotency-Key", newKey()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.version").exists());
		mockMvc.perform(delete("/api/v2/trades/{id}", only.getId()).header("Idempotency-Key", newKey()).param("version", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fields.version").value("版本號 version 必須是整數"));
	}

	@Test
	void 交易id不存在或不是數字_404_TRADE_NOT_FOUND() throws Exception {
		mockMvc.perform(delete("/api/v2/trades/{id}", "99999").header("Idempotency-Key", newKey()).param("version", "0"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
		mockMvc.perform(put("/api/v2/trades/{id}", "abc").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"trade_date\":\"2026-03-02\",\"shares\":100,\"price\":\"600\",\"version\":0}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
	}

	// ===== v1 的錯誤格式不變 =====

	@Test
	void v1的錯誤回應沒有多出fields或details() throws Exception {
		mockMvc.perform(get("/api/v1/holdings/{id}", 99999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.fields").doesNotExist())
				.andExpect(jsonPath("$.error.details").doesNotExist());
	}
}
