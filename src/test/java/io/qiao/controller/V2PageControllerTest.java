package io.qiao.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hamcrest.Matchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.qiao.quote.QuoteCache;
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

	@Autowired
	private QuoteCache quoteCache;

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
				// 已實現收益率：16,706.75 ÷ 122,674.25 × 100 = 13.62（摘要卡與持股列，優化 realized-return）
				.andExpect(content().string(Matchers.containsString("+13.62%")))
				// 名稱是乾淨的（v1 的「請更新 App 2.0」加註只在 v1 API，M1）
				.andExpect(content().string(Matchers.not(Matchers.containsString("請更新 App 2.0"))))
				.andExpect(content().string(Matchers.containsString("href=\"/v2/sell?code=2330\"")))
				.andExpect(content().string(Matchers.containsString("href=\"/v2/holdings/2330\"")));
	}

	// ===== 大數字（優化 v2-number-display，D-40；D-45 門檻改成 100 萬）=====
	// 買 20,000 股 @600：成交 12,000,000、手續費 17,100 → 持有成本 12,017,100；收盤 650 → 市值 13,000,000
	// 未實現 13,000,000 − 18,525 − 39,000 − 12,017,100 = 925,375（未滿 100 萬，不簡寫）

	private void buyLarge2330() {
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 20_000, new BigDecimal("600.00"));
	}

	@Test
	void 大數字_總覽表格簡寫成萬_虛線與完整金額提示_未滿100萬照舊() throws Exception {
		buyLarge2330();

		mockMvc.perform(get("/v2"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString(">1,300 萬<")))                 // 表格的市值
				// 提示框（D-43）：完整數字放在 data-full（CSS 馬上顯示），tabindex 讓鍵盤也移得到；不用 title（有延遲）
				.andExpect(content().string(Matchers.containsString("data-full=\"13,000,000.00 元\"")))
				.andExpect(content().string(Matchers.containsString("tabindex=\"0\"")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("title=\"13,000,000.00 元\""))))
				.andExpect(content().string(Matchers.containsString("v2-abbr")))
				.andExpect(content().string(Matchers.containsString(">+925,375.00<")))              // 未實現：未滿 100 萬
				.andExpect(content().string(Matchers.containsString(">20,000<")))                    // 股數
				.andExpect(content().string(Matchers.containsString("table-responsive")));           // 放不下時有左右捲軸
	}

	@Test
	void 大數字_總覽摘要卡顯示完整金額加單位元_不簡寫() throws Exception {
		buyLarge2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString(">12,017,100.00</span><span class=\"v2-unit\">元</span>"))) // 持有成本
				.andExpect(content().string(Matchers.containsString(">13,000,000.00</span><span class=\"v2-unit\">元</span>"))) // 市值
				.andExpect(content().string(Matchers.containsString(">+925,375.00</span><span class=\"v2-unit\">元</span>")))   // 未實現
				// 滿 100 萬的卡片：移上去或點一下顯示「約 X 萬元」（D-43）；未滿 100 萬的未實現沒有提示
				.andExpect(content().string(Matchers.containsString("data-full=\"約 1,202 萬元\"")))  // 持有成本 12,017,100 → 1,201.71 萬
				.andExpect(content().string(Matchers.containsString("data-full=\"約 1,300 萬元\"")))  // 市值
				.andExpect(content().string(Matchers.not(Matchers.containsString("data-full=\"約 +93 萬元\""))));
	}

	@Test
	void 大數字_摘要卡有提示框但沒有虛線_表格的簡寫兩者都有() throws Exception {
		buyLarge2330();
		String html = mockMvc.perform(get("/v2")).andReturn().getResponse().getContentAsString();

		// D-44：卡片是完整金額不是簡寫 → 只有提示框（v2-tip），不加虛線（v2-abbr）
		String cardTag = tagWith(html, "data-full=\"約 1,202 萬元\"");
		assertThat(cardTag).contains("v2-tip").doesNotContain("v2-abbr");
		// 表格的簡寫：虛線＋提示框
		String tableTag = tagWith(html, "data-full=\"13,000,000.00 元\"");
		assertThat(tableTag).contains("v2-abbr").contains("v2-tip");
	}

	/** 找出含有指定屬性的那個開頭標籤（例如 <span class="…" data-full="…" tabindex="0">），屬性順序不拘 */
	private static String tagWith(String html, String attribute) {
		Matcher matcher = Pattern.compile("<span[^>]*" + Pattern.quote(attribute) + "[^>]*>").matcher(html);
		assertThat(matcher.find()).as("找不到 " + attribute).isTrue();
		return matcher.group();
	}

	@Test
	void 取不到收盤價_總覽與明細寫出無法計算與原因() throws Exception {
		// D-50：原本市值、未實現、收益率只顯示三個「—」，看不出原因
		buyAndSell2330();
		quoteCache.putUnavailable("2330", LocalDateTime.of(2026, 11, 20, 14, 0)); // 官方連不上

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString("colspan=\"2\""))) // 市值、收益率（D-54 起損益欄只在明細）
				.andExpect(content().string(Matchers.containsString("無法計算")))
				.andExpect(content().string(Matchers.containsString("尚未取得收盤價")));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("無法計算")))
				.andExpect(content().string(Matchers.containsString("尚未取得收盤價")));
	}

	@Test
	void 手機版_表格有卡片模式與欄位名稱_頁首有折疊選單() throws Exception {
		// D-51：手機上表格變卡片（v2-table-cards＋每格 data-label）；頁首在窄螢幕收成「☰」
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString("v2-table-cards")))
				.andExpect(content().string(Matchers.containsString("data-label=\"市值\"")))
				.andExpect(content().string(Matchers.containsString("data-label=\"收益率\"")))
				// D-56：頁首不再有「☰」與「持股總覽」頁籤，改成直接顯示台北時間（手機分兩行）
				.andExpect(content().string(Matchers.not(Matchers.containsString("navbar-toggler"))))
				.andExpect(content().string(Matchers.containsString("v2-clock")))
				.andExpect(content().string(Matchers.containsString("col-12 col-md-6 col-xl")));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("v2-table-cards")))
				.andExpect(content().string(Matchers.containsString("data-label=\"手續費\"")))
				.andExpect(content().string(Matchers.containsString("v2-card-actions")));
		mockMvc.perform(get("/v2/buy"))
				.andExpect(content().string(Matchers.containsString("col-12 col-lg-8")));
	}

	@Test
	void 標籤設計_市場別收盤日期收益率_損益欄只在明細() throws Exception {
		// D-54
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString("class=\"v2-market v2-market-twse\"")))  // 上市標籤
				.andExpect(content().string(Matchers.containsString("v2-date-chip")))                         // 收盤價日期
				.andExpect(content().string(Matchers.containsString("v2-pct v2-pct-up")))                     // 摘要卡收益率（+5.50%、+13.62%）
				.andExpect(content().string(Matchers.containsString("bi-caret-up-fill")))
				// 持股表不再有未實現、已實現損益兩欄（摘要卡的標題仍在）
				.andExpect(content().string(Matchers.not(Matchers.containsString(">未實現損益</th>"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString(">已實現損益</th>"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("data-label=\"未實現損益\""))));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("class=\"v2-market v2-market-twse\"")))
				.andExpect(content().string(Matchers.containsString("v2-pct v2-pct-up")))
				.andExpect(content().string(Matchers.containsString(">已實現損益</th>")));                     // 明細的交易表照舊有
	}

	@Test
	void 換頁時的紅色提示_開頭加錯誤() throws Exception {
		// D-49：和表單的紅色錯誤框一致，開頭是粗體「錯誤！」
		mockMvc.perform(get("/v2").flashAttr("errorMessage", "2330 目前沒有持股（已全部賣出），不能賣出"))
				.andExpect(content().string(Matchers.containsString(
						"<strong>錯誤！</strong><span>2330 目前沒有持股（已全部賣出），不能賣出</span>")));
	}

	@Test
	void 大數字_明細交易表的成交金額簡寫_摘要卡完整金額() throws Exception {
		buyLarge2330();

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString(">1,200 萬<")))                 // 成交金額 12,000,000
				.andExpect(content().string(Matchers.containsString("data-full=\"12,000,000.00 元\"")))
				.andExpect(content().string(Matchers.containsString(">17,100.00<")))                 // 手續費：未滿 100 萬
				.andExpect(content().string(Matchers.containsString(">+925,375.00</span><span class=\"v2-unit\">元</span>")))
				.andExpect(content().string(Matchers.containsString("table-responsive")));
	}

	@Test
	void 大數字_明細的手續費證交稅已實現與股數_滿100萬都簡寫() throws Exception {
		// 買 2,000,000 股 @1,000：成交 20 億、手續費 2,850,000（20 億 × 0.1425%）→ 成本 2,002,850,000
		// 賣 1,000,000 股 @1,000：成交 10 億、手續費 1,425,000、證交稅 3,000,000（0.3%）、分攤成本 1,001,425,000
		//                         已實現 1,000,000,000 − 1,425,000 − 3,000,000 − 1,001,425,000 = −5,850,000
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 2_000_000, new BigDecimal("1000.00"));
		tradeService.sell("2330", LocalDate.of(2026, 5, 15), 1_000_000, new BigDecimal("1000.00"));

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString(">285 萬<")))     // 買進手續費 2,850,000 → 285 萬
				.andExpect(content().string(Matchers.containsString("data-full=\"2,850,000.00 元\"")))
				.andExpect(content().string(Matchers.containsString(">143 萬<")))     // 賣出手續費 1,425,000 → 142.5 萬 → 143（四捨五入）
				.andExpect(content().string(Matchers.containsString(">300 萬<")))     // 證交稅 3,000,000
				.andExpect(content().string(Matchers.containsString("data-full=\"3,000,000.00 元\"")))
				.andExpect(content().string(Matchers.containsString(">−585 萬<")))    // 已實現 −5,850,000（同樣的規則，帶正負號）
				.andExpect(content().string(Matchers.containsString("data-full=\"−5,850,000.00 元\"")))
				.andExpect(content().string(Matchers.containsString(">200 萬股<")))   // 股數 2,000,000
				.andExpect(content().string(Matchers.containsString(">100 萬股<")))   // 交易後股數 1,000,000
				.andExpect(content().string(Matchers.containsString("表格中 100 萬以上的數字")));
	}

	@Test
	void 一般金額_表格沒有簡寫也沒有虛線() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("v2-abbr"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString(" 萬<"))));
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
				.andExpect(content().string(Matchers.containsString("+13.62%")))     // 這次賣出與整檔的已實現收益率
				.andExpect(content().string(Matchers.containsString("600 股（零股）")))
				.andExpect(content().string(Matchers.containsString("data-delete-trade")))
				.andExpect(content().string(Matchers.containsString("/edit\"")));
	}

	@Test
	void 明細_推估日期標示推估() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600")); // App 1.0 新增：沒有買進日期，用今天代替

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("v2-estimated")))
				// D-52：旁邊的「?」點一下跳出說明（Bootstrap Popover，點別處關閉）
				.andExpect(content().string(Matchers.containsString("data-bs-toggle=\"popover\"")))
				.andExpect(content().string(Matchers.containsString("data-bs-trigger=\"focus\"")))
				.andExpect(content().string(Matchers.containsString("這是 1.0 時期的舊資料，當時沒有記錄買進日期")));
	}

	@Test
	void 明細_日期不是推估的交易_沒有問號() throws Exception {
		buyAndSell2330(); // 2.0 記的交易都有實際日期

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("data-bs-toggle=\"popover\""))));
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

	// ===== 買進、賣出、修改頁（C9）：送出由 JavaScript 呼叫 /api/v2，這裡驗證頁面資料 =====

	@Test
	void 買進頁_從持股進來代號固定_持股與休市日放進頁面() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2/buy").param("code", "２３３０"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("\"fixedCode\": \"2330\"")))
				.andExpect(content().string(Matchers.containsString("\"total_cost\":\"368022.75\"")))
				.andExpect(content().string(Matchers.containsString("\"shares\":600")))
				// C8-6 b：內附 2021～2027 的休市日（例如 2025 農曆除夕）放進頁面，開頁不查官方
				.andExpect(content().string(Matchers.containsString("\"2025-01-28\"")))
				.andExpect(content().string(Matchers.containsString("\"2021\"")))
				.andExpect(content().string(Matchers.containsString("/js/v2-form.js")));
	}

	@Test
	void 買進頁_記一筆買進_代號自己輸入() throws Exception {
		mockMvc.perform(get("/v2/buy"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("\"fixedCode\": null")));
	}

	@Test
	void 賣出頁_持有股數與成本放進頁面() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2/sell").param("code", "2330"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("\"code\": \"2330\"")))
				.andExpect(content().string(Matchers.containsString("\"shares\":600")))
				.andExpect(content().string(Matchers.containsString("\"total_cost\":\"368022.75\"")))
				// 成功訊息要顯示公司名稱。Thymeleaf 把中文轉成 \\u 跳脫（合法的 JSON，JSON.parse 後就是「台積電」）
				.andExpect(content().string(Matchers.containsString("\"name\": \"\\u53F0\\u7A4D\\u96FB\"")))
				.andExpect(content().string(Matchers.containsString("全部賣出")));
	}

	// ===== 對話框（D-53）：?dialog=1 只回表單片段；頁面資料是合法 JSON（前端用 JSON.parse 讀） =====

	@Test
	void 對話框_買進賣出修改加dialog參數_只回表單片段_沒有頁首() throws Exception {
		buyAndSell2330();
		Trade trade = tradeRepository.findAll().get(0);

		for (String url : new String[] { "/v2/buy?dialog=1", "/v2/buy?code=2330&dialog=1", "/v2/sell?code=2330&dialog=1",
				"/v2/trades/" + trade.getId() + "/edit?dialog=1" }) {
			mockMvc.perform(get(url))
					.andExpect(status().isOk())
					.andExpect(content().string(Matchers.containsString("data-v2-form=")))
					.andExpect(content().string(Matchers.containsString("class=\"v2-form-data\"")))
					.andExpect(content().string(Matchers.containsString("data-v2-cancel")))
					.andExpect(content().string(Matchers.not(Matchers.containsString("<nav"))))      // 沒有頁首
					.andExpect(content().string(Matchers.not(Matchers.containsString("<html"))));
		}
	}

	@Test
	void 對話框_三種表單的頁面資料都是合法JSON() throws Exception {
		buyAndSell2330();
		Trade trade = tradeRepository.findAll().get(0);

		JsonNode buy = formData("/v2/buy?code=2330");
		assertThat(buy.get("fixedCode").asText()).isEqualTo("2330");
		assertThat(buy.get("held").get("2330").get("shares").asLong()).isEqualTo(600);
		assertThat(buy.get("closedDays").get("2025").isArray()).isTrue();

		JsonNode sell = formData("/v2/sell?code=2330");
		assertThat(sell.get("name").asText()).isEqualTo("台積電");
		assertThat(sell.get("held").get("total_cost").asText()).isEqualTo("368022.75");
		assertThat(sell.get("bondEtfExempt").get("until").asText()).isNotBlank();

		JsonNode edit = formData("/v2/trades/" + trade.getId() + "/edit");
		assertThat(edit.get("tradeId").asLong()).isEqualTo(trade.getId());
		assertThat(edit.get("side").asText()).isEqualTo("BUY");
		assertThat(edit.get("shares").asLong()).isEqualTo(300);

		assertThat(formData("/v2/buy").get("fixedCode").isNull()).isTrue();
	}

	@Test
	void 對話框_總覽與明細的買進賣出修改連結會開對話框() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString("id=\"v2-form-dialog\"")))
				.andExpect(content().string(Matchers.containsString("data-v2-dialog href=\"/v2/buy?code=2330\"")))
				.andExpect(content().string(Matchers.containsString("/js/v2-forms.js")));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("id=\"v2-form-dialog\"")))
				.andExpect(content().string(Matchers.containsString("data-v2-dialog href=\"/v2/trades/")))
				.andExpect(content().string(Matchers.containsString("/js/v2-forms.js")));
	}

	@Test
	void 版面調整_頁首時間_試算說明在算式下方_明細置中_賣出不顯示資料時間() throws Exception {
		// D-56
		buyAndSell2330();

		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.not(Matchers.containsString(">持股總覽</a>"))))  // 頁籤拿掉，logo 連回總覽
				.andExpect(content().string(Matchers.containsString("title=\"回持股總覽\"")))
				.andExpect(content().string(Matchers.containsString("（台北時間）")));
		mockMvc.perform(get("/v2/buy?dialog=1"))
				.andExpect(content().string(Matchers.containsString("<span>＋ 手續費</span>")))
				.andExpect(content().string(Matchers.containsString("手續費：0.1425%，捨去到元，最低 20 元")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("會向官方確認代號，最多需要約 10 秒"))));
		mockMvc.perform(get("/v2/sell?code=2330&dialog=1"))
				.andExpect(content().string(Matchers.containsString("id=\"calc-tax-rate\"")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("held-time"))))
				.andExpect(content().string(Matchers.containsString("flex-wrap")));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("<th class=\"v2-center\">買賣</th>")))
				.andExpect(content().string(Matchers.containsString("<th class=\"v2-num v2-center\">交易後股數</th>")))
				.andExpect(content().string(Matchers.containsString("v2-modal-form")));
	}

	@Test
	void 對話框_表單三行_明細有確認對話框_總覽置中() throws Exception {
		// D-55：日期自己一行、數量＋價格一行（col-md-6）；刪除確認用網站自己的對話框；總覽的市場～操作置中
		buyAndSell2330();

		mockMvc.perform(get("/v2/buy?dialog=1"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("col-md-3"))))
				.andExpect(content().string(Matchers.containsString("class=\"col-12 col-md-6\"")));
		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				.andExpect(content().string(Matchers.containsString("id=\"v2-confirm\"")))
				.andExpect(content().string(Matchers.containsString("modal-lg")));
		mockMvc.perform(get("/v2"))
				.andExpect(content().string(Matchers.containsString("<th class=\"v2-center\">市場</th>")))
				.andExpect(content().string(Matchers.containsString("<th class=\"v2-center\">操作</th>")));
	}

	/** 取出頁面裡 <script type="application/json" class="v2-form-data"> 的內容，用 Jackson 解析（不合法就會丟例外） */
	private JsonNode formData(String url) throws Exception {
		String html = mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString();
		Matcher matcher = Pattern.compile("class=\"v2-form-data\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).as(url + " 找不到 v2-form-data").isTrue();
		return new ObjectMapper().readTree(matcher.group(1));
	}

	@Test
	void 賣出頁_已全部賣出_回總覽說明() throws Exception {
		tradeService.buy("2363", LocalDate.of(2026, 3, 2), 1000, new BigDecimal("10.00"));
		tradeService.sell("2363", LocalDate.of(2026, 5, 15), 1000, new BigDecimal("10.50"));

		mockMvc.perform(get("/v2/sell").param("code", "2363"))
				.andExpect(redirectedUrl("/v2"))
				.andExpect(flash().attribute("errorMessage", "2363 目前沒有持股（已全部賣出），不能賣出"));
	}

	@Test
	void 賣出頁_沒有持有或沒帶代號_回總覽() throws Exception {
		mockMvc.perform(get("/v2/sell").param("code", "2330")).andExpect(redirectedUrl("/v2"));
		mockMvc.perform(get("/v2/sell")).andExpect(redirectedUrl("/v2"));
	}

	@Test
	void 賣出頁_資料異常_回總覽請先到明細修正() throws Exception {
		Long brokenId = holdingService.create("2317", 1, new BigDecimal("10")).getId();
		tradeRepository.save(Trade.create(brokenId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock)));

		mockMvc.perform(get("/v2/sell").param("code", "2317"))
				.andExpect(redirectedUrl("/v2"))
				.andExpect(flash().attribute("errorMessage", Matchers.startsWith("2317 的交易資料異常，請先到明細修正")));
	}

	@Test
	void 修改頁_原本的內容與版本號放進頁面_推估日期提示() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600")); // App 1.0 新增：推估日期
		Trade trade = tradeRepository.findAll().get(0);

		mockMvc.perform(get("/v2/trades/{id}/edit", trade.getId()))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("\"tradeId\": " + trade.getId())))
				.andExpect(content().string(Matchers.containsString("\"version\": " + trade.getVersion())))
				.andExpect(content().string(Matchers.containsString("\"shares\": 1000")))
				.andExpect(content().string(Matchers.containsString("value=\"600.00\"")))
				.andExpect(content().string(Matchers.containsString("value=\"" + trade.getTradeDate() + "\""))) // YYYY-MM-DD
				.andExpect(content().string(Matchers.containsString("日期是<strong>推估</strong>的")));
	}

	@Test
	void 修改頁_資料異常的持股也能修改_才能修正錯的那一筆() throws Exception {
		Long brokenId = holdingService.create("2317", 1, new BigDecimal("10")).getId();
		Trade brokenSell = tradeRepository.save(Trade.create(brokenId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock)));

		mockMvc.perform(get("/v2/trades/{id}/edit", brokenSell.getId()))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("\"side\": \"SELL\"")));
	}

	@Test
	void 修改頁_找不到這筆交易或id不是數字_回總覽() throws Exception {
		mockMvc.perform(get("/v2/trades/{id}/edit", 999999))
				.andExpect(redirectedUrl("/v2"))
				.andExpect(flash().attribute("errorMessage", "找不到這筆交易，可能已在另一個裝置刪除"));
		mockMvc.perform(get("/v2/trades/{id}/edit", "abc"))
				.andExpect(redirectedUrl("/v2"));
	}

	@Test
	void 明細頁_刪除按鈕帶版本號與說明_載入刪除程式() throws Exception {
		buyAndSell2330();

		mockMvc.perform(get("/v2/holdings/{code}", "2330"))
				// 格式：動作 代號 名稱 股數 × 價格（日期）
				.andExpect(content().string(Matchers.containsString("data-description=\"賣出 2330 台積電 200 股 × 700.00 元（2026-08-20）\"")))
				.andExpect(content().string(Matchers.containsString("data-version=")))
				.andExpect(content().string(Matchers.containsString("/js/v2-trade-delete.js")))
				// D-48：修改、刪除是按鈕（原本是文字連結）
				.andExpect(content().string(Matchers.containsString("class=\"btn btn-sm btn-outline-secondary me-1\"")))
				.andExpect(content().string(Matchers.containsString("class=\"btn btn-sm btn-outline-danger\" data-delete-trade")));
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
