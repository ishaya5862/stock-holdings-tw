package io.qiao.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;

/**
 * App 1.0 API：股票代號的全形、空白會自動統一（修正第 2 題，網頁與 API 共用 StockCodes.normalize）。
 * App 的代號欄位用一般文字鍵盤，中文輸入法可能打出全形或多一個空白。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiCodeNormalizationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingRepository holdingRepository;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "測試股", Market.TWSE, new BigDecimal("100.00"), LocalDate.of(2026, 11, 19)));
	}

	@ParameterizedTest
	@CsvSource({
			"'２３３０', 2330",
			"'　００６７９ｂ　', 00679B",
			"'23 30', 2330",
			"' 0050 ', 0050" })
	void 新增_全形與空白自動統一後存檔(String input, String saved) throws Exception {
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + input + "\",\"lots\":1,\"buy_price\":100}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value(saved));

		assertThat(holdingRepository.existsByCode(saved)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "　", "　 　" })
	void 新增_只有全形空白_當成沒填_回400請輸入股票代號(String input) throws Exception {
		// Java 的 trim() 不認得全形空白；先統一寫法後變成空字串，才會被 @NotBlank 擋下
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + input + "\",\"lots\":1,\"buy_price\":100}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.message").value("請輸入股票代號"));
	}

	@ParameterizedTest
	@CsvSource({ "ＡＢＣ, ABC", "２３, 23" })
	void 新增_全形但格式不可能存在_404訊息顯示統一後的代號(String input, String normalized) throws Exception {
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + input + "\",\"lots\":1,\"buy_price\":100}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value(startsWith("查無股票代號 " + normalized + "：")));
	}
}
