package io.qiao.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.HoldingService;
import io.qiao.service.TradeService;

/**
 * 第 7 題回報二：債券 ETF（00679B）停徵證交稅，App 1.0（v1）、App 2.0（v2）、2.0 網頁都要一起修正。
 *
 * 停徵期限在這裡設成 2099-12-31：測試結果不會因為「執行測試的日期」過了 2026 年底而改變，
 * 也順便驗證「延長案三讀後只要改設定 app.tax.bond-etf-exempt-until」真的有效。
 *
 * 00679B 3 張（3,000 股）@30，收盤 31：
 * 買 90,000 + 手續費 128 = 90,128；估賣 93,000 − 手續費 132 − 證交稅 0 = 92,868 → 未實現 +2,740
 * （修正前扣證交稅 93 元 → +2,647）
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.tax.bond-etf-exempt-until=2099-12-31")
class BondEtfTaxTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private TradeService tradeService;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "元大美債20年", Market.TWSE, new BigDecimal("31.00"), LocalDate.of(2026, 11, 20)));
	}

	@Test
	void App1點0_未實現損益不扣證交稅() throws Exception {
		holdingService.create("00679B", 3, new BigDecimal("30"));

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("00679B"))
				.andExpect(jsonPath("$[0].pnl").value(2740));
	}

	@Test
	void App2點0_已實現與未實現都不扣證交稅() throws Exception {
		tradeService.buy("00679B", LocalDate.of(2026, 3, 2), 3000, new BigDecimal("30.00"));
		tradeService.sell("00679B", LocalDate.of(2026, 11, 14), 1000, new BigDecimal("31.00"));

		// 賣 1,000 股：31,000 − 手續費 44 − 證交稅 0 − 分攤成本 30,042.67 = 913.33
		mockMvc.perform(get("/api/v2/holdings/{code}/trades", "00679B"))
				.andExpect(jsonPath("$[1].tax").value("0.00"))
				.andExpect(jsonPath("$[1].realized_pnl").value("913.33"));
		// 剩 2,000 股：估賣 62,000 − 手續費 88 − 證交稅 0 − 剩餘成本 60,085.33 = 1,826.67
		mockMvc.perform(get("/api/v2/holdings/{code}", "00679B"))
				.andExpect(jsonPath("$.unrealized_pnl").value("1826.67"));
	}

	@Test
	void 網頁賣出頁_放進停徵期間供試算() throws Exception {
		tradeService.buy("00679B", LocalDate.of(2026, 3, 2), 3000, new BigDecimal("30.00"));

		mockMvc.perform(get("/v2/sell").param("code", "00679B"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("until: \"2099-12-31\"")))
				.andExpect(content().string(Matchers.containsString("from: \"2017-01-01\"")));
	}
}
