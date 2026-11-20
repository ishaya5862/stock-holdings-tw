package io.qiao.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import jakarta.persistence.EntityManager;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.HoldingService;

/**
 * App 1.0 API 讀取類測試（第 5 題第 2 段）：逐欄位檢查名稱、型別、可否為 null、HTTP 狀態碼。
 * 型別要求來自 App 原始碼 Contract.java：整數欄位不能是小數、可為 null 的欄位一定要出現。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiHoldingReadTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private QuoteCache quoteCache;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private StockLookupService stockLookupService;

	/**
	 * 模擬「市場別空白的舊資料」：直接用 SQL 改股票表。
	 * 改完要清掉 Hibernate 的一級快取（同一個交易內已載入的 Stock 物件），否則後面讀到的還是改之前的舊物件。
	 * （正式執行時每個請求都是新的快取，不會有這個問題；這是測試在同一個交易裡做所有事才需要。）
	 */
	private void setStockDirectly(String setAndWhere) {
		entityManager.flush();
		jdbcTemplate.update("UPDATE stocks SET " + setAndWhere);
		entityManager.clear();
	}

	@BeforeEach
	void setUp() {
		// 2330 收盤 1100（題目例子 1）；0050 收盤 145.50（例子 2）
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 19)));
		when(stockLookupService.lookup("0050")).thenReturn(new StockQuote(
				"0050", "元大台灣50", Market.TWSE, new BigDecimal("145.50"), LocalDate.of(2026, 11, 19)));
	}

	// ===== GET /api/v1/holdings =====

	@Test
	void 清單_沒有持股回空陣列() throws Exception {
		mockMvc.perform(get("/api/v1/holdings").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("[]"));
	}

	@Test
	void 清單_持股物件逐欄位符合規格() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000.00")).getId();

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(id.intValue()))
				.andExpect(jsonPath("$[0].id", instanceOf(Integer.class)))           // 整數
				.andExpect(jsonPath("$[0].code").value("2330"))                       // 字串
				.andExpect(jsonPath("$[0].name").value("台積電"))                     // 字串
				.andExpect(jsonPath("$[0].market").value("TWSE"))                     // TWSE／TPEX
				.andExpect(jsonPath("$[0].lots").value(1))
				.andExpect(jsonPath("$[0].lots", instanceOf(Integer.class)))         // 整數
				.andExpect(jsonPath("$[0].buy_price").value(1000.00))                // 數字
				.andExpect(jsonPath("$[0].current_price").value(1100.00))
				.andExpect(jsonPath("$[0].price_date").value("2026-11-19"))          // YYYY-MM-DD
				.andExpect(jsonPath("$[0].pnl").value(93708))
				.andExpect(jsonPath("$[0].pnl", instanceOf(Integer.class)))          // 整數，不能是 93708.0
				.andExpect(jsonPath("$[0].return_pct").value(9.36));
	}

	@Test
	void 清單_金額是JSON整數_欄位名稱是小寫加底線() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(content().string(containsString("\"pnl\":93708,")))      // 不是 93708.00
				.andExpect(content().string(containsString("\"buy_price\":1000.00")))
				.andExpect(content().string(containsString("\"return_pct\":9.36")))
				.andExpect(content().string(not(containsString("buyPrice"))));
	}

	@Test
	void 清單_取不到收盤價_四個欄位都要出現且為null() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		quoteCache.putUnavailable("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0]", hasKey("current_price")))
				.andExpect(jsonPath("$[0]", hasKey("price_date")))
				.andExpect(jsonPath("$[0]", hasKey("pnl")))
				.andExpect(jsonPath("$[0]", hasKey("return_pct")))
				.andExpect(jsonPath("$[0].current_price").value(nullValue()))
				.andExpect(jsonPath("$[0].price_date").value(nullValue()))
				.andExpect(jsonPath("$[0].pnl").value(nullValue()))
				.andExpect(jsonPath("$[0].return_pct").value(nullValue()))
				.andExpect(jsonPath("$[0].name").value("台積電"));                   // 名稱仍來自股票表
	}

	@Test
	void 清單_市場別空白的舊資料不輸出_避免App整頁資料格式錯誤() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		holdingService.create("0050", 2, new BigDecimal("150.00"));
		setStockDirectly("name = NULL, market = NULL WHERE code = '0050'");
		quoteCache.remove("0050");

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].code").value("2330"));
	}

	// ===== GET /api/v1/holdings/{id} =====

	@Test
	void 單筆_回傳持股物件() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000.00")).getId();

		mockMvc.perform(get("/api/v1/holdings/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.intValue()))
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.pnl").value(93708));
	}

	@Test
	void 單筆_找不到_404_NOT_FOUND規格錯誤格式() throws Exception {
		mockMvc.perform(get("/api/v1/holdings/{id}", 99999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("找不到這筆持股，可能已被刪除"));
	}

	@Test
	void 單筆_id不是數字_也是404_NOT_FOUND() throws Exception {
		mockMvc.perform(get("/api/v1/holdings/abc"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 單筆_市場別空白的舊資料_404() throws Exception {
		Long id = holdingService.create("0050", 2, new BigDecimal("150.00")).getId();
		setStockDirectly("market = NULL WHERE code = '0050'");
		quoteCache.remove("0050");

		mockMvc.perform(get("/api/v1/holdings/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ===== GET /api/v1/summary =====

	@Test
	void 總計_規格範例_例子1加例子2() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		holdingService.create("0050", 2, new BigDecimal("150.00"));

		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(1301852))
				.andExpect(jsonPath("$.total_cost", instanceOf(Integer.class)))
				.andExpect(jsonPath("$.market_value").value(1391000))
				.andExpect(jsonPath("$.market_value", instanceOf(Integer.class)))
				.andExpect(jsonPath("$.pnl").value(83576))
				.andExpect(jsonPath("$.pnl", instanceOf(Integer.class)))
				.andExpect(jsonPath("$.return_pct").value(6.42))
				.andExpect(jsonPath("$.quote_missing").value(0))
				.andExpect(jsonPath("$.quote_missing", instanceOf(Integer.class)));
	}

	@Test
	void 總計_沒有持股_金額為0_收益率null() throws Exception {
		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(0))
				.andExpect(jsonPath("$.market_value").value(0))
				.andExpect(jsonPath("$.pnl").value(0))
				.andExpect(jsonPath("$", hasKey("return_pct")))
				.andExpect(jsonPath("$.return_pct").value(nullValue()))
				.andExpect(jsonPath("$.quote_missing").value(0));
	}

	@Test
	void 總計_全部取不到收盤價_照規格收益率0_未取得筆數() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		quoteCache.putUnavailable("2330", LocalDateTime.of(2026, 11, 20, 14, 0));

		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(jsonPath("$.total_cost").value(1001425))
				.andExpect(jsonPath("$.market_value").value(0))
				.andExpect(jsonPath("$.pnl").value(0))
				.andExpect(jsonPath("$.return_pct").value(0.0))
				.andExpect(jsonPath("$.quote_missing").value(1));
	}

	@Test
	void 總計_市場別空白的舊資料不算進總計() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000.00"));
		holdingService.create("0050", 2, new BigDecimal("150.00"));
		setStockDirectly("market = NULL WHERE code = '0050'");
		quoteCache.remove("0050");

		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(jsonPath("$.total_cost").value(1001425)) // 只有 2330
				.andExpect(jsonPath("$.quote_missing").value(0));
	}
}
