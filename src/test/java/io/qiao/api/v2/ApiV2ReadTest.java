package io.qiao.api.v2;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.HoldingService;
import io.qiao.service.TradeService;

/**
 * App 2.0 讀取 API 測試（第 6 題 C5）：逐欄位檢查名稱、型別（金額是字串、股數是整數）、可為 null 的欄位一定出現、狀態碼。
 *
 * 決定（PARTB_NOTES B-22）：C5-1 代號識別、C5-2 金額字串、C5-3 小寫底線、C5-4 含 0 股、
 * C5-5 總計同 v1 規則＋已實現、C5-6 交易明細附重算結果與版本、C5-7 錯誤格式同 v1、C5-8 行情狀態。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiV2ReadTest {

	private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
	private static final LocalDate D2 = LocalDate.of(2026, 5, 15);
	private static final LocalDate D3 = LocalDate.of(2026, 8, 20);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private QuoteCache quoteCache;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		// 官方行情：收盤 650（買進還沒持有的代號時查詢，結果放進快取 → 狀態 OK）
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	/** C1 的台積電範例：剩 600 股、成本 368,022.75、平均成本 613.37、已實現 16,706.75；收盤 650 → 未實現 20,252.25、5.50% */
	private void tsmcExample() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));
		tradeService.buy("2330", D2, 500, new BigDecimal("620.00"));
		tradeService.sell("2330", D3, 200, new BigDecimal("700.00"));
	}

	// ===== 持股 =====

	@Test
	void 持股清單_欄位名稱與型別_金額是兩位小數的字串() throws Exception {
		tsmcExample();

		mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].code").value("2330"))
				.andExpect(jsonPath("$[0].name").value("台積電"))
				.andExpect(jsonPath("$[0].market").value("TWSE"))
				.andExpect(jsonPath("$[0].shares").value(600))
				.andExpect(jsonPath("$[0].shares").isNumber())
				.andExpect(jsonPath("$[0].average_cost").value("613.37"))
				.andExpect(jsonPath("$[0].total_cost").value("368022.75"))
				.andExpect(jsonPath("$[0].total_cost").isString())
				.andExpect(jsonPath("$[0].realized_pnl").value("16706.75"))
				.andExpect(jsonPath("$[0].quote_status").value("OK"))
				.andExpect(jsonPath("$[0].close_price").value("650.00"))
				.andExpect(jsonPath("$[0].price_date").value("2026-11-20"))
				.andExpect(jsonPath("$[0].market_value").value("390000.00"))
				.andExpect(jsonPath("$[0].unrealized_pnl").value("20252.25"))
				.andExpect(jsonPath("$[0].return_pct").value("5.50"))
				.andExpect(jsonPath("$[0].version").isNumber());
	}

	@Test
	void 全部賣光的持股也在清單_股數0_平均成本與未實現是null但欄位一定出現() throws Exception {
		// 第 4 題例子 3：1,000 股 @10 → 全部賣 @10.5 → 已實現 429
		tradeService.buy("2363", D1, 1000, new BigDecimal("10.00"));
		tradeService.sell("2363", D2, 1000, new BigDecimal("10.50"));

		mockMvc.perform(get("/api/v2/holdings/{code}", "2363"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shares").value(0))
				.andExpect(jsonPath("$", Matchers.hasKey("average_cost")))
				.andExpect(jsonPath("$.average_cost").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.total_cost").value("0.00"))
				.andExpect(jsonPath("$.realized_pnl").value("429.00"))
				.andExpect(jsonPath("$", Matchers.hasKey("unrealized_pnl")))
				.andExpect(jsonPath("$.unrealized_pnl").value(Matchers.nullValue()));
	}

	@Test
	void 取不到收盤價_行情狀態未取得_未實現是null_其他照算() throws Exception {
		tsmcExample();
		quoteCache.putUnavailable("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/api/v2/holdings/{code}", "2330"))
				.andExpect(jsonPath("$.quote_status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.close_price").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.market_value").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.unrealized_pnl").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.average_cost").value("613.37"))
				.andExpect(jsonPath("$.last_close_price").value("650.00")); // 股票表的上次收盤價
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(jsonPath("$.quote_missing").value(1))
				.andExpect(jsonPath("$.total_cost").value("368022.75")) // 取不到收盤價的也算進成本
				.andExpect(jsonPath("$.unrealized_pnl").value("0.00"));
	}

	@Test
	void 代號全形或小寫也查得到() throws Exception {
		tsmcExample();

		mockMvc.perform(get("/api/v2/holdings/{code}", "２３３０"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("2330"));
	}

	@Test
	void 沒有持有過的代號_404_NOT_HELD_錯誤格式同v1() throws Exception {
		mockMvc.perform(get("/api/v2/holdings/{code}", "9999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_HELD"))
				.andExpect(jsonPath("$.error.message").value("目前沒有持有 9999"));
		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "9999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_HELD"));
	}

	@Test
	void 沒有持股_清單是空陣列_總計是0_收益率null() throws Exception {
		mockMvc.perform(get("/api/v2/holdings")).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(jsonPath("$.total_cost").value("0.00"))
				.andExpect(jsonPath("$.realized_pnl").value("0.00"))
				.andExpect(jsonPath("$.return_pct").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.holding_count").value(0));
	}

	// ===== 交易明細 =====

	@Test
	void 交易明細_依日期排序_附重算的手續費稅已實現與版本號() throws Exception {
		tsmcExample();

		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "2330"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				// 買 300 @600：180,000，手續費 256（256.5 捨去），沒有稅與已實現
				.andExpect(jsonPath("$[0].side").value("BUY"))
				.andExpect(jsonPath("$[0].trade_date").value("2026-03-02"))
				.andExpect(jsonPath("$[0].date_estimated").value(false))
				.andExpect(jsonPath("$[0].shares").value(300))
				.andExpect(jsonPath("$[0].price").value("600.00"))
				.andExpect(jsonPath("$[0].amount").value("180000.00"))
				.andExpect(jsonPath("$[0].fee").value("256.00"))
				.andExpect(jsonPath("$[0].tax").value(Matchers.nullValue()))
				.andExpect(jsonPath("$[0].realized_pnl").value(Matchers.nullValue()))
				.andExpect(jsonPath("$[0].shares_after").value(300))
				.andExpect(jsonPath("$[0].id").isNumber())
				.andExpect(jsonPath("$[0].version").isNumber())
				// 賣 200 @700：140,000，手續費 199、證交稅 420、分攤成本 122,674.25、已實現 16,706.75
				.andExpect(jsonPath("$[2].side").value("SELL"))
				.andExpect(jsonPath("$[2].fee").value("199.00"))
				.andExpect(jsonPath("$[2].tax").value("420.00"))
				.andExpect(jsonPath("$[2].cost_of_sold").value("122674.25"))
				.andExpect(jsonPath("$[2].realized_pnl").value("16706.75"))
				.andExpect(jsonPath("$[2].shares_after").value(600));
	}

	@Test
	void 一點零轉入的交易_日期標記推估() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600")); // App 1.0 新增：沒有日期 → 推估

		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "2330"))
				.andExpect(jsonPath("$[0].date_estimated").value(true))
				.andExpect(jsonPath("$[0].shares").value(1000));
	}

	// ===== 總計 =====

	@Test
	void 總計_持有中的成本市值未實現_加上含已全部賣出的已實現() throws Exception {
		tsmcExample();
		tradeService.buy("2363", D1, 1000, new BigDecimal("10.00"));
		tradeService.sell("2363", D2, 1000, new BigDecimal("10.50")); // 已全部賣出，已實現 429

		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value("368022.75"))
				.andExpect(jsonPath("$.market_value").value("390000.00"))
				.andExpect(jsonPath("$.unrealized_pnl").value("20252.25"))
				.andExpect(jsonPath("$.return_pct").value("5.50"))
				.andExpect(jsonPath("$.realized_pnl").value("17135.75")) // 16,706.75 + 429
				.andExpect(jsonPath("$.holding_count").value(1))
				.andExpect(jsonPath("$.quote_missing").value(0));
	}

	// ===== 已實現收益率（優化 realized-return，D-37）：已實現損益 ÷ 分攤成本 × 100，字串、兩位小數 =====

	@Test
	void 已實現收益率_持股與賣出那一筆都是13點62_買進那一筆是null但欄位一定出現() throws Exception {
		tsmcExample(); // 已實現 16,706.75 ÷ 分攤成本 122,674.25 × 100 = 13.6188… → 13.62

		mockMvc.perform(get("/api/v2/holdings/{code}", "2330"))
				.andExpect(jsonPath("$.realized_return_pct").value("13.62"))
				.andExpect(jsonPath("$.realized_return_pct").isString());
		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "2330"))
				.andExpect(jsonPath("$[0]", Matchers.hasKey("realized_return_pct")))
				.andExpect(jsonPath("$[0].realized_return_pct").value(Matchers.nullValue()))
				.andExpect(jsonPath("$[2].realized_return_pct").value("13.62"));
	}

	@Test
	void 已實現收益率_沒有賣出過_持股與總計都是null但欄位一定出現() throws Exception {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));

		mockMvc.perform(get("/api/v2/holdings/{code}", "2330"))
				.andExpect(jsonPath("$", Matchers.hasKey("realized_return_pct")))
				.andExpect(jsonPath("$.realized_return_pct").value(Matchers.nullValue()));
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(jsonPath("$", Matchers.hasKey("realized_return_pct")))
				.andExpect(jsonPath("$.realized_return_pct").value(Matchers.nullValue()));
	}

	@Test
	void 已實現收益率_總計用加總計算_含已全部賣出_不是各檔百分比平均() throws Exception {
		tsmcExample();                                                 // 已實現 16,706.75、分攤成本 122,674.25（13.62%）
		tradeService.buy("2363", D1, 1000, new BigDecimal("10.00"));
		tradeService.sell("2363", D2, 1000, new BigDecimal("10.50")); // 已實現 429、分攤成本 10,020（4.28%）

		// (16,706.75 + 429) ÷ (122,674.25 + 10,020) × 100 = 17,135.75 ÷ 132,694.25 × 100 = 12.9137… → 12.91
		// （兩檔百分比平均是 (13.62 + 4.28) ÷ 2 = 8.95，不是這個）
		mockMvc.perform(get("/api/v2/summary"))
				.andExpect(jsonPath("$.realized_pnl").value("17135.75"))
				.andExpect(jsonPath("$.realized_return_pct").value("12.91"));
		mockMvc.perform(get("/api/v2/holdings/{code}", "2363"))
				.andExpect(jsonPath("$.realized_return_pct").value("4.28"));
	}

	// ===== 和 v1 分開（M1、M3、M4） =====

	@Test
	void v2的名稱不會有v1的更新加註() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600"));
		tradeService.buy("2330", D1, 500, new BigDecimal("620.00")); // 有零股、多次買進 → v1 會加註

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(jsonPath("$[0].name").value(Matchers.containsString("【請更新 App 2.0")));
		mockMvc.perform(get("/api/v2/holdings/{code}", "2330"))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.shares").value(1500)); // 真實股數，不捨去成張
	}

	@Test
	void v2沒有淘汰標頭_App2點0帶XClient可以正常呼叫() throws Exception {
		mockMvc.perform(get("/api/v2/holdings").header("X-Client", "app2/2.0.0"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Deprecation"))
				.andExpect(header().doesNotExist("Link"));
	}
}
