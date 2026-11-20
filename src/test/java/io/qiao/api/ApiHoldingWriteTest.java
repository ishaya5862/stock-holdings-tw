package io.qiao.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

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
 * App 1.0 API 寫入類測試（第 5 題第 3 段）：新增 201、修改 200、刪除 204，以及嚴格的輸入檢查。
 *
 * 請求內容照 App 1.0 實際會送的格式（App 原始碼 EditActivity）：
 * 張數打 1.5 → 送字串 "1.5"；打 abc → 送字串 "abc"；空白 → 送 null；正常 → 送整數。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiHoldingWriteTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private QuoteCache quoteCache;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 19)));
	}

	private ResultActions postJson(String json) throws Exception {
		return mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions putJson(Object id, String json) throws Exception {
		return mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static void expectError(ResultActions actions, int status, String code, String message) throws Exception {
		actions.andExpect(status().is(status))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));
	}

	// ===== POST /api/v1/holdings：成功 =====

	@Test
	void 新增_規格範例的請求_201_回傳新建的持股物件與Location() throws Exception {
		postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/holdings/")))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.market").value("TWSE"))
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.buy_price").value(1000.00))
				.andExpect(jsonPath("$.current_price").value(1100.00))
				.andExpect(jsonPath("$.price_date").value("2026-11-19"))
				.andExpect(jsonPath("$.pnl").value(93708))
				.andExpect(jsonPath("$.return_pct").value(9.36));

		assertThat(holdingRepository.existsByCode("2330")).isTrue();
	}

	@Test
	void 新增_小寫英文代號自動轉大寫() throws Exception {
		postJson("{\"code\":\"00679b\",\"lots\":3,\"buy_price\":28.5}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("00679B"));
	}

	@Test
	void 新增_買進價980點5這種一位小數可以() throws Exception {
		postJson("{\"code\":\"2330\",\"lots\":2,\"buy_price\":980.5}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.buy_price").value(980.50));
	}

	// ===== POST：邊界值（剛好在範圍內要成功） =====

	@ParameterizedTest
	@CsvSource({ "1, 1000", "999, 1000", "1, 0.01", "1, 99999999.99" })
	void 新增_邊界值剛好在範圍內_201(int lots, String buyPrice) throws Exception {
		postJson("{\"code\":\"2330\",\"lots\":" + lots + ",\"buy_price\":" + buyPrice + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lots").value(lots))
				.andExpect(jsonPath("$.pnl").isNumber());
	}

	@Test
	void 新增_最大金額_999張乘以買進價上限_盈虧仍是整數不溢位() throws Exception {
		// 999 張 × 1,000 股 × 99,999,999.99 ≈ 1×10^14，long 上限約 9×10^18，不會溢位
		MvcResult result = postJson("{\"code\":\"2330\",\"lots\":999,\"buy_price\":99999999.99}")
				.andExpect(status().isCreated())
				.andReturn();
		// 直接看 JSON 原文：金額必須是整數寫法（沒有小數點、不是 1E+14 科學記號）
		String body = result.getResponse().getContentAsString();
		assertThat(body).containsPattern("\"pnl\":-\\d+,");
		assertThat(body).contains("\"buy_price\":99999999.99,");
		assertThat(body).doesNotContain("E+");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"1.5",       // 小數（不能被截成 1）
			"\"1.5\"",   // App 打 1.5 時實際送出的字串
			"\"1\"",     // 字串的 1 也不行（不自動轉換）
			"\"abc\"",   // App 打 abc
			"null",      // App 空白
			"0", "1000", "-1",
			"true",
			"1e2" })     // 科學記號是小數
	void 新增_張數不對_400(String lots) throws Exception {
		expectError(postJson("{\"code\":\"2330\",\"lots\":" + lots + ",\"buy_price\":1000}"),
				400, "VALIDATION_ERROR", "張數必須是 1～999 的整數");
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增_張數沒送_400() throws Exception {
		expectError(postJson("{\"code\":\"2330\",\"buy_price\":1000}"), 400, "VALIDATION_ERROR", "張數必須是 1～999 的整數");
	}

	// ===== POST：買進價錯誤 → 400 =====

	@ParameterizedTest
	@ValueSource(strings = {
			"\"abc\"", "\"1000\"",  // 字串（App 打 abc 會送字串）
			"null",
			"0", "-1",
			"10.123",               // 超過兩位小數
			"123456789" })          // 超過 8 位整數（資料表 DECIMAL(10,2)）
	void 新增_買進價不對_400(String buyPrice) throws Exception {
		expectError(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":" + buyPrice + "}"),
				400, "VALIDATION_ERROR", "買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字");
		assertThat(holdingRepository.count()).isZero();
	}

	// ===== POST：代號 =====

	@ParameterizedTest
	@ValueSource(strings = { "\"\"", "\"   \"", "null" })
	void 新增_代號空白_400(String code) throws Exception {
		expectError(postJson("{\"code\":" + code + ",\"lots\":1,\"buy_price\":1000}"),
				400, "VALIDATION_ERROR", "請輸入股票代號");
	}

	@Test
	void 新增_代號是數字不是字串_400() throws Exception {
		expectError(postJson("{\"code\":2330,\"lots\":1,\"buy_price\":1000}"),
				400, "VALIDATION_ERROR", "股票代號必須是文字，例如 \"2330\"");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ABC", "23", "1234567", "2330AB", "台積電" })
	void 新增_代號格式不可能存在_404查無_不查官方(String code) throws Exception {
		postJson("{\"code\":\"" + code + "\",\"lots\":1,\"buy_price\":1000}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
		verify(stockLookupService, never()).lookup(anyString());
	}

	@Test
	void 新增_官方查不到_404_STOCK_NOT_FOUND() throws Exception {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));

		postJson("{\"code\":\"9999\",\"lots\":1,\"buy_price\":10}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.startsWith("查無股票代號 9999")));
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增_重複代號_409_訊息照規格範例() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		expectError(postJson("{\"code\":\"2330\",\"lots\":2,\"buy_price\":900}"), 409, "DUPLICATE_CODE", "2330 已在持股中");
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 新增_官方連不上_503_QUOTE_UNAVAILABLE_不儲存() throws Exception {
		when(stockLookupService.lookup("2330")).thenThrow(new QuoteUnavailableException("測試：連不上"));

		expectError(postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"),
				503, "QUOTE_UNAVAILABLE", "行情來源暫時無法連線，請稍後再試");
		assertThat(holdingRepository.count()).isZero();
	}

	// ===== POST：請求內容本身有問題 =====

	@Test
	void 新增_空物件_三個欄位都沒填_一次列出全部_順序固定() throws Exception {
		expectError(postJson("{}"), 400, "VALIDATION_ERROR",
				"請輸入股票代號；張數必須是 1～999 的整數；買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字");
	}

	@Test
	void 新增_張數和買進價都超出範圍_兩個錯誤合併成一句() throws Exception {
		expectError(postJson("{\"code\":\"2330\",\"lots\":0,\"buy_price\":0}"), 400, "VALIDATION_ERROR",
				"張數必須是 1～999 的整數；買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字");
	}

	@Test
	void 新增_型別錯誤加上沒填_讀取JSON時就被擋_只回型別錯誤那一則() throws Exception {
		// 型別錯誤（"abc"）在讀取 JSON 時就停止，還輪不到檢查「沒填」，這是技術上的限制
		expectError(postJson("{\"code\":\"2330\",\"lots\":null,\"buy_price\":\"abc\"}"), 400, "VALIDATION_ERROR",
				"買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{", "[]", "\"2330\"", "" })
	void 新增_不是JSON物件_400(String body) throws Exception {
		expectError(postJson(body), 400, "VALIDATION_ERROR", "請求內容格式不正確，必須是 JSON 物件");
	}

	@Test
	void 新增_不是JSON格式送出_400() throws Exception {
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.TEXT_PLAIN).content("code=2330"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 新增_多送不認得的欄位_忽略_照常新增() throws Exception {
		postJson("{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000,\"note\":\"多的欄位\"}")
				.andExpect(status().isCreated());
	}

	// ===== PUT /api/v1/holdings/{id} =====

	@Test
	void 修改_規格範例的請求_200_回傳修改後的持股物件() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		putJson(id, "{\"lots\":2,\"buy_price\":980.5}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.intValue()))
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.buy_price").value(980.50));

		Holding saved = holdingRepository.findById(id).orElseThrow();
		assertThat(saved.getLots()).isEqualTo(2);
		assertThat(saved.getBuyPrice()).isEqualByComparingTo("980.50");
	}

	@Test
	void 修改_請求帶了不同的代號_忽略_代號不會被改() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		putJson(id, "{\"code\":\"9999\",\"lots\":3,\"buy_price\":1000}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("2330"));
	}

	@Test
	void 修改_找不到_404() throws Exception {
		expectError(putJson(99999, "{\"lots\":1,\"buy_price\":10}"), 404, "NOT_FOUND", "找不到這筆持股，可能已被刪除");
	}

	@Test
	void 修改_id不是數字_404() throws Exception {
		putJson("abc", "{\"lots\":1,\"buy_price\":10}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 修改_張數是字串的1點5_400_資料不變() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		expectError(putJson(id, "{\"lots\":\"1.5\",\"buy_price\":1000}"), 400, "VALIDATION_ERROR", "張數必須是 1～999 的整數");
		assertThat(holdingRepository.findById(id).orElseThrow().getLots()).isEqualTo(1);
	}

	@Test
	void 修改_買進價三位小數_400() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		expectError(putJson(id, "{\"lots\":1,\"buy_price\":10.123}"), 400, "VALIDATION_ERROR", "買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字");
	}

	@Test
	void 修改_市場別空白的舊資料_App看不到_404() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();
		makeLegacy("2330");

		putJson(id, "{\"lots\":2,\"buy_price\":1000}").andExpect(status().isNotFound());
		assertThat(holdingRepository.findById(id).orElseThrow().getLots()).isEqualTo(1);
	}

	// ===== DELETE /api/v1/holdings/{id} =====

	@Test
	void 刪除_204_沒有內容_再刪一次404() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		mockMvc.perform(delete("/api/v1/holdings/{id}", id))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		assertThat(holdingRepository.existsById(id)).isFalse();

		expectError(mockMvc.perform(delete("/api/v1/holdings/{id}", id)), 404, "NOT_FOUND", "找不到這筆持股，可能已被刪除");
	}

	@Test
	void 刪除_市場別空白的舊資料_404_不刪() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();
		makeLegacy("2330");

		mockMvc.perform(delete("/api/v1/holdings/{id}", id)).andExpect(status().isNotFound());
		assertThat(holdingRepository.existsById(id)).isTrue();
	}

	/** 模擬市場別空白的舊資料（直接改股票表，並清掉同一交易內的舊快取） */
	private void makeLegacy(String code) {
		entityManager.flush();
		jdbcTemplate.update("UPDATE stocks SET market = NULL WHERE code = ?", code);
		entityManager.clear();
		// 快取（記憶體）裡有新增時放進去的行情，也要移除，模擬「排程還沒查到」
		quoteCache.remove(code);
	}
}
