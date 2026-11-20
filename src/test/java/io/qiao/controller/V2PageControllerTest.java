package io.qiao.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
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

import io.qiao.calculation.TradeSide;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.TradeRepository;
import io.qiao.service.HoldingService;
import io.qiao.service.TradeService;

/**
 * 2.0 新網頁的讀取頁面（第 6 題 C8）：總覽 /v2、明細 /v2/holdings/{code}。
 *
 * 數字用 2.0 預覽版手算過的例子（2330：3/2 買 300@600、5/15 買 500@620、8/20 賣 200@700，收盤 650）：
 * 剩 600 股、持有成本 368,022.75、平均成本 613.37、已實現 +16,706.75、未實現 +20,252.25（+5.50%）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V2PageControllerTest {

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

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	private void buyAndSell2330() {
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00"));
		tradeService.buy("2330", LocalDate.of(2026, 5, 15), 500, new BigDecimal("620.00"));
		tradeService.sell("2330", LocalDate.of(2026, 8, 20), 200, new BigDecimal("700.00"));
	}

	// ===== 總覽 =====

	@Test
	void 總覽_顯示預覽版手算的數字_金額到分() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("持股記帳<span class=\"v2-version\">2.0</span>")))
				.andExpect(content().string(Matchers.containsString(">600<")))
				.andExpect(content().string(Matchers.containsString("613.37")))
				.andExpect(content().string(Matchers.containsString("368,022.75")))
				.andExpect(content().string(Matchers.containsString("390,000.00")))
				.andExpect(content().string(Matchers.containsString("+20,252.25")))
				.andExpect(content().string(Matchers.containsString("+5.50%")))
				.andExpect(content().string(Matchers.containsString("+16,706.75")))
				// 名稱是乾淨的（v1 的「請更新 App 2.0」加註只在 v1 API，M1）
				.andExpect(content().string(Matchers.not(Matchers.containsString("請更新 App 2.0"))))
				.andExpect(content().string(Matchers.containsString("href=\"/v2/sell?code=2330\"")))
				.andExpect(content().string(Matchers.containsString("href=\"/v2/holdings/2330\"")));
	}

	@Test
	void 總覽_全部賣出的持股淡化並標示_不能賣_有顯示開關() throws Exception {
		tradeService.buy("2363", LocalDate.of(2026, 3, 2), 1000, new BigDecimal("10.00"));
		tradeService.sell("2363", LocalDate.of(2026, 5, 15), 1000, new BigDecimal("10.50"));

		mockMvc.perform(get("/v2"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("v2-closed")))
				.andExpect(content().string(Matchers.containsString("已全部賣出")))
				.andExpect(content().string(Matchers.containsString("id=\"show-closed\"")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("/v2/sell?code=2363"))))
				.andExpect(content().string(Matchers.containsString("/v2/buy?code=2363"))); // 賣光還能再買
	}

	@Test
	void 總覽_沒有全部賣出的持股_不顯示開關() throws Exception {
		buyAndSell2330();
		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("id=\"show-closed\""))));
	}

	@Test
	void 總覽_資料異常的那一檔整列顯示原因_其他照常_摘要另外說明() throws Exception {
		buyAndSell2330();
		Long brokenId = holdingService.create("2317", 1, new BigDecimal("10")).getId();
		tradeRepository.save(Trade.create(brokenId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock))); // 模擬直接改資料庫：賣出早於買進

		mockMvc.perform(get("/v2"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("資料異常，暫時無法計算")))
				.andExpect(content().string(Matchers.containsString("2026-03-02 賣出 1000 股，但當時只持有 0 股")))
				.andExpect(content().string(Matchers.containsString("檔交易資料異常")))
				.andExpect(content().string(Matchers.containsString("368,022.75"))) // 總計只算正常的 2330
				.andExpect(content().string(Matchers.not(Matchers.containsString("/v2/sell?code=2317"))));
	}

	@Test
	void 總覽_沒有持股() throws Exception {
		mockMvc.perform(get("/v2"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("目前沒有持股")));
	}

	// ===== 明細 =====

	@Test
	void 明細_每一筆交易附手續費稅已實現與交易後股數() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("2026-08-20")))
				.andExpect(content().string(Matchers.containsString("v2-side-sell")))
				.andExpect(content().string(Matchers.containsString("140,000.00"))) // 賣出成交金額
				.andExpect(content().string(Matchers.containsString("199.00")))     // 賣出手續費
				.andExpect(content().string(Matchers.containsString("420.00")))     // 證交稅
				.andExpect(content().string(Matchers.containsString("+16,706.75")))
				.andExpect(content().string(Matchers.containsString("600 股（零股）")))
				.andExpect(content().string(Matchers.containsString("data-delete-trade")))
				.andExpect(content().string(Matchers.containsString("/edit\"")));
	}

	@Test
	void 明細_推估日期標示推估() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600")); // App 1.0 新增：沒有買進日期，用今天代替

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("v2-estimated")));
	}

	@Test
	void 明細_資料異常_說明原因並照樣列出交易讓使用者修正() throws Exception {
		Long brokenId = holdingService.create("2317", 1, new BigDecimal("10")).getId();
		tradeRepository.save(Trade.create(brokenId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock)));

		mockMvc.perform(get("/v2/holdings/{code}", "2317"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("id=\"data-error\"")))
				.andExpect(content().string(Matchers.containsString("只持有 0 股")))
				.andExpect(content().string(Matchers.containsString("v2-side-sell")))
				.andExpect(content().string(Matchers.containsString("v2-side-buy")));
	}

	@Test
	void 明細_代號寫法不同也找得到() throws Exception {
		buyAndSell2330();
		mockMvc.perform(get("/v2/holdings/{code}", "２３３０"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("+16,706.75")));
	}

	@Test
	void 明細_沒有這檔持股_例如另一個裝置剛刪掉_回總覽並提示() throws Exception {
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/v2"))
				.andExpect(flash().attribute("errorMessage", "目前沒有這檔持股，可能已在另一個裝置刪除"));
		mockMvc.perform(get("/v2/holdings/{code}", "ABC"))
				.andExpect(redirectedUrl("/v2"));
	}

	// ===== 首頁、第一版網頁（C8-2 a） =====

	@Test
	void 首頁仍顯示標題與台北時間_主要按鈕到2點0_第一版改成次要連結() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("持股記帳")))
				.andExpect(content().string(Matchers.containsString("href=\"/v2\"")))
				.andExpect(content().string(Matchers.containsString("href=\"/holdings\"")));
	}

	@Test
	void 第一版網頁照舊_橫幅連到2點0() throws Exception {
		buyAndSell2330();
		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("前往 2.0 網頁")));
	}
}
