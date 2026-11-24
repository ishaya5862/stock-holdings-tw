package io.qiao.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.service.HoldingService;
import jakarta.persistence.EntityManager;

/**
 * 持股網頁測試：用 MockMvc 模擬瀏覽器送出表單，確認驗證錯誤會顯示在畫面上、資料不會被儲存。
 * 查官方行情的 StockLookupService 換成假的（@MockitoBean），測試不會連網。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HoldingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@MockitoBean
	private StockLookupService stockLookupService;

	@Autowired
	private QuoteCache quoteCache;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	// ===== 第 4 題：清單的盈虧、收益率與頁面最上方的總計 =====

	/** 讓 0050 的收盤價是 145.50（題目例子 2），其他代號維持預設的 1,100.00（例子 1 的收盤價） */
	private void stubEtf0050() {
		when(stockLookupService.lookup("0050")).thenReturn(new StockQuote(
				"0050", "元大台灣50", Market.TWSE, new BigDecimal("145.50"), LocalDate.of(2026, 11, 19)));
	}

	@Test
	void 盈虧_題目例子1賺錢_紅色_例子2虧錢_綠色_總計為規格範例() throws Exception {
		stubEtf0050();
		holdingService.create("2330", 1, new BigDecimal("1000.00")); // 收盤 1,100 → +93,708、+9.36%
		holdingService.create("0050", 2, new BigDecimal("150.00"));  // 收盤 145.5 → -10,132、-3.37%

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				// 每一筆
				.andExpect(content().string(containsString("+93,708")))
				.andExpect(content().string(containsString("+9.36%")))
				.andExpect(content().string(containsString("-10,132")))
				.andExpect(content().string(containsString("-3.37%")))
				.andExpect(content().string(containsString("text-profit")))
				.andExpect(content().string(containsString("text-loss")))
				// 總計（規格範例：total_cost 1,301,852、market_value 1,391,000、pnl 83,576、6.42%）
				.andExpect(content().string(containsString("1,301,852")))
				.andExpect(content().string(containsString("1,391,000")))
				.andExpect(content().string(containsString("+83,576 元")))
				.andExpect(content().string(containsString("+6.42%")));
	}

	@Test
	void 盈虧_取不到收盤價_顯示無法計算與原因_總計加註未計入() throws Exception {
		stubEtf0050();
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		holdingService.create("0050", 2, new BigDecimal("150.00"));
		quoteCache.putUnavailable("0050", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("無法計算")))
				.andExpect(content().string(containsString("尚未取得收盤價")))
				.andExpect(content().string(containsString("筆未取得收盤價，未計入市值與盈虧")))
				.andExpect(content().string(containsString("1,301,852")))    // 總成本兩筆都算
				.andExpect(content().string(containsString("+93,708 元")));  // 總盈虧只有 2330
	}

	@Test
	void 盈虧_近期無成交資料_原因顯示近期無成交資料() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		quoteCache.putNoRecentTrade("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("無法計算")))
				.andExpect(content().string(containsString("<div class=\"small text-muted\">近期無成交資料</div>")));
	}

	@Test
	void 總計_全部取不到收盤價_顯示暫時無法計算_不顯示誤導的0() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		quoteCache.remove("2330"); // 模擬剛啟動、還沒查到

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("尚未取得收盤價，暫時無法計算")))
				.andExpect(content().string(not(containsString("0.00%"))))
				.andExpect(content().string(containsString("1,001,425"))); // 總成本仍顯示
	}

	@Test
	void 總計_沒有持股_顯示說明() throws Exception {
		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("目前沒有持股，新增後即可計算盈虧")));
	}

	@Test
	void 清單收盤價_查詢成功_顯示收盤價與日期() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000")); // 新增時查到的行情會直接放進快取

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("1,100.00")))
				.andExpect(content().string(containsString("11/19")))
				// 反向檢查：查詢成功時不應出現其他狀態的文字（避免文字其實來自 HTML 註解而誤判通過）
				.andExpect(content().string(not(containsString("未取得"))))
				.andExpect(content().string(not(containsString("更新中"))));
	}

	@Test
	void 清單收盤價_查詢失敗_顯示未取得與上次收盤價_整頁正常() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));
		quoteCache.putUnavailable("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("未取得")))
				.andExpect(content().string(containsString("上次收盤價")))
				.andExpect(content().string(containsString("1,100.00")));
	}

	@Test
	void 清單收盤價_近期無成交資料_顯示中性說明與最後收盤價() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000")); // 上次收盤價 1,100.00（2026/11/19）
		quoteCache.putNoRecentTrade("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("近期無成交資料")))
				.andExpect(content().string(containsString("可能停止交易或已下市")))
				.andExpect(content().string(containsString("最後收盤價")))
				.andExpect(content().string(containsString("2026/11/19")))
				.andExpect(content().string(not(containsString("查無此代號"))));
	}

	@Test
	void 清單收盤價_啟動後還沒查過_顯示更新中() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));
		quoteCache.remove("2330"); // 模擬伺服器剛重開、排程還沒輪到

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("更新中")));
	}

	@BeforeEach
	void setUp() {
		// 預設：任何代號都查得到
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 19)));
	}

	@Test
	void 新增查無此股票_代號欄位顯示錯誤且不儲存() throws Exception {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));

		mockMvc.perform(post("/holdings").param("code", "9999").param("lots", "1").param("buyPrice", "10"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("查無股票代號 9999")));

		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增時官方連不上_顯示稍後再試且不儲存() throws Exception {
		when(stockLookupService.lookup("2330")).thenThrow(new QuoteUnavailableException("測試：連不上"));

		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("行情來源暫時無法連線，請稍後再試")));

		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增後清單顯示名稱與市場() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(containsString("台積電")))
				.andExpect(content().string(containsString("上市")));
	}

	@Test
	void 清單頁可以開啟() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("持股清單")))
				.andExpect(content().string(containsString("2330")))
				.andExpect(content().string(containsString("1,000.00")));
	}

	@Test
	void 新增成功_轉址回清單並顯示訊息() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "1000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("successMessage", "已新增 2330"));

		assertThat(holdingRepository.existsByCode("2330")).isTrue();
	}

	@Test
	void 重複新增_顯示錯誤且不儲存() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "2").param("buyPrice", "900"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("2330 已在持股中")));

		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 張數是小數_顯示中文錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1.5").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("張數必須是整數")));

		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 張數超出範圍_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1000").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("張數最多 999 張")));
	}

	@Test
	void 買進價超過兩位小數_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "10.123"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價最多兩位小數")));
	}

	@Test
	void 買進價超過上限_訊息說明上限_不是只說兩位小數() throws Exception {
		// 123456789 沒有小數，若只顯示「最多兩位小數」會誤導使用者
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "123456789"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("最大 99,999,999.99")));
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 邊界值剛好在範圍內_999張與買進價上限_可以新增() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "999").param("buyPrice", "99999999.99"))
				.andExpect(redirectedUrl("/holdings"));
		mockMvc.perform(post("/holdings").param("code", "0050").param("lots", "1").param("buyPrice", "0.01"))
				.andExpect(redirectedUrl("/holdings"));
		assertThat(holdingRepository.count()).isEqualTo(2);

		// 清單頁要能正常顯示這麼大的金額（不會溢位或整頁錯誤）
		mockMvc.perform(get("/holdings"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("99,999,999.99")));
	}

	@Test
	void 買進價為0_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "0"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價必須大於 0")));
	}

	@Test
	void 代號格式錯誤_後端擋下_就算前端檢查被繞過也不儲存() throws Exception {
		for (String badCode : new String[] { "台積電", "233", "1234567", "A2330", "2330AB" }) {
			mockMvc.perform(post("/holdings").param("code", badCode).param("lots", "1").param("buyPrice", "10"))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("股票代號格式不正確（4～6 位數字，最後可加 1 個英文字母")));
		}
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 代號含英文字母的ETF可以新增_小寫自動轉大寫() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "00679b").param("lots", "3").param("buyPrice", "28.5"))
				.andExpect(redirectedUrl("/holdings"));

		assertThat(holdingRepository.existsByCode("00679B")).isTrue();
	}

	@Test
	void 全形代號和空白自動統一後新增() throws Exception {
		// 瀏覽器沒開 JavaScript 時，後端也要能把全形、空白統一（前端轉換只是方便，後端才是最後防線）
		mockMvc.perform(post("/holdings").param("code", "　２３ ３０　").param("lots", "1").param("buyPrice", "100"))
				.andExpect(redirectedUrl("/holdings"));

		assertThat(holdingRepository.existsByCode("2330")).isTrue();
	}

	@Test
	void 新增頁帶有前後端共用的代號規則() throws Exception {
		mockMvc.perform(get("/holdings/new"))
				.andExpect(content().string(containsString("data-pattern=\"^[0-9]{4,6}[A-Za-z]?$\"")))
				.andExpect(content().string(containsString("js/holding-form.js")));
	}

	@Test
	void 代號空白_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", " ").param("lots", "1").param("buyPrice", "10"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("請輸入股票代號")));
	}

	@Test
	void 修改時送上不同代號_代號不會被改() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(post("/holdings/{id}", saved.getId())
				.param("code", "9999").param("lots", "5").param("buyPrice", "980.5"))
				.andExpect(redirectedUrl("/holdings"));

		Holding updated = holdingService.findById(saved.getId());
		assertThat(updated.getCode()).isEqualTo("2330");
		assertThat(holdingService.findViewById(saved.getId()).lots()).isEqualTo(5L);
	}

	// ===== 修改頁：代號只顯示、不放輸入框 =====

	@Test
	void 修改頁_代號名稱市場用資訊區塊顯示_沒有代號輸入框() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(get("/holdings/{id}/edit", saved.getId()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id=\"stock-info\"")))
				.andExpect(content().string(containsString("2330")))
				.andExpect(content().string(containsString("台積電")))
				.andExpect(content().string(containsString("上市")))
				.andExpect(content().string(containsString("代號不能修改")))
				// 代號不在表單欄位裡：沒有 id="code" 的輸入框，也不會送出 name="code"
				.andExpect(content().string(not(containsString("id=\"code\""))))
				.andExpect(content().string(not(containsString("name=\"code\""))));
	}

	// ===== 預估買進成本（前端試算，費率由後端提供） =====

	/** 費率必須是一般小數寫法（不能是 1.425E-3 科學記號），前端才能正確解析 */
	private static final String FEE_ATTRIBUTES = "data-fee-rate=\"0.001425\" data-min-fee=\"20\"";

	@Test
	void 新增頁_有預估買進成本區塊_費率取自後端() throws Exception {
		mockMvc.perform(get("/holdings/new"))
				.andExpect(content().string(containsString("id=\"cost-preview\"")))
				.andExpect(content().string(containsString(FEE_ATTRIBUTES)))
				.andExpect(content().string(containsString("預估買進成本")))
				// 用 HTML 內建的摺疊（details／summary），預設收合（沒有 open 屬性）
				.andExpect(content().string(containsString("<details class=\"cost-preview mb-4\" id=\"cost-preview\"")))
				.andExpect(content().string(containsString("id=\"cost-preview-summary\"")))
				.andExpect(content().string(not(containsString("<details class=\"cost-preview mb-4\" id=\"cost-preview\" open"))));
	}

	@Test
	void 修改頁_也有預估買進成本區塊() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(get("/holdings/{id}/edit", saved.getId()))
				.andExpect(content().string(containsString("id=\"cost-preview\"")))
				.andExpect(content().string(containsString(FEE_ATTRIBUTES)));
	}

	@Test
	void 輸入錯誤重新顯示時_預估買進成本的費率還在() throws Exception {
		// @ModelAttribute 方法每次請求都會執行，驗證失敗回到表單也要有費率，前端才能繼續試算
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "0").param("buyPrice", "1000"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(FEE_ATTRIBUTES)));
	}

	@Test
	void 新增頁_仍然有代號輸入框_沒有股票資訊區塊() throws Exception {
		mockMvc.perform(get("/holdings/new"))
				.andExpect(content().string(containsString("id=\"code\"")))
				.andExpect(content().string(not(containsString("id=\"stock-info\""))));
	}

	@Test
	void 修改_表單不帶代號_照常修改成功() throws Exception {
		// 修改頁已經不送代號，後端要能正常處理（代號以資料庫為準）
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(post("/holdings/{id}", saved.getId()).param("lots", "3").param("buyPrice", "950"))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("successMessage", "已修改 2330"));

		HoldingView updated = holdingService.findViewById(saved.getId()); // 2.0：張數、買進價改由交易紀錄算出
		assertThat(updated.lots()).isEqualTo(3L);
		assertThat(updated.buyPrice()).isEqualByComparingTo("950");
	}

	@Test
	void 修改_輸入錯誤重新顯示時_股票資訊區塊還在() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(post("/holdings/{id}", saved.getId()).param("lots", "0").param("buyPrice", "950"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id=\"stock-info\"")))
				.andExpect(content().string(containsString("台積電")))
				.andExpect(content().string(not(containsString("name=\"code\""))));
	}

	@Test
	void 修改頁_舊資料還沒補上名稱和市場_顯示破折號_頁面正常() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));
		// 模擬第 2 題時期留下的舊資料：stocks 只有代號，名稱、市場還沒由排程補上
		entityManager.flush();
		jdbcTemplate.update("UPDATE stocks SET name = NULL, market = NULL WHERE code = ?", "2330");
		entityManager.clear();
		// 名稱會優先用快取（記憶體）的行情，新增時已放進去 → 也要移除，模擬「排程還沒查到」
		quoteCache.remove("2330");

		mockMvc.perform(get("/holdings/{id}/edit", saved.getId()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("—")))
				.andExpect(content().string(not(containsString("台積電"))))
				.andExpect(content().string(not(containsString("badge text-bg-secondary"))));
	}

	@Test
	void 找不到的持股_回清單並顯示訊息() throws Exception {
		mockMvc.perform(get("/holdings/{id}/edit", 99999))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("errorMessage", "找不到這筆持股，可能已被刪除"));
	}

	@Test
	void 網址的id不是數字_回清單() throws Exception {
		mockMvc.perform(get("/holdings/abc/edit"))
				.andExpect(redirectedUrl("/holdings"));
	}

	@Test
	void 刪除後轉址回清單() throws Exception {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		mockMvc.perform(post("/holdings/{id}/delete", saved.getId()))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("successMessage", "已刪除 2330"));

		assertThat(holdingRepository.count()).isZero();
	}
}
