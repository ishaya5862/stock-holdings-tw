package io.qiao.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.TradeService;

/**
 * 2.0 網頁的「查代號」（D-55）：買進表單的代號離開輸入框就查，馬上顯示名稱與上市／上櫃。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V2StockControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TradeService tradeService;

	@MockitoBean
	private StockLookupService stockLookupService;

	@Test
	void 以前持有過的代號_直接回答_不查官方() throws Exception {
		when(stockLookupService.lookup(anyString())).thenReturn(
				new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00")); // 股票表有 2330
		clearInvocations(stockLookupService);

		mockMvc.perform(get("/v2/stocks/{code}", "2330"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("2330"))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.market").value("TWSE"));
		verify(stockLookupService, never()).lookup(anyString());
	}

	@Test
	void 沒看過的代號_向官方確認_代號會先統一寫法() throws Exception {
		when(stockLookupService.lookup("6488")).thenReturn(
				new StockQuote("6488", "環球晶", Market.TPEX, new BigDecimal("506.00"), LocalDate.of(2026, 11, 20)));

		mockMvc.perform(get("/v2/stocks/{code}", "６４８８"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("環球晶"))
				.andExpect(jsonPath("$.market").value("TPEX"));
	}

	@Test
	void 查無代號_404_錯誤格式同API() throws Exception {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));

		mockMvc.perform(get("/v2/stocks/{code}", "9999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value(startsWith("查無股票代號 9999")));
	}

	@Test
	void 官方連不上_503() throws Exception {
		when(stockLookupService.lookup("2317")).thenThrow(new QuoteUnavailableException("連線逾時"));

		mockMvc.perform(get("/v2/stocks/{code}", "2317"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("QUOTE_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("行情來源暫時無法連線，請稍後再試"));
	}

	@Test
	void 代號格式不可能存在_400_不查官方() throws Exception {
		mockMvc.perform(get("/v2/stocks/{code}", "ABC"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verify(stockLookupService, never()).lookup(anyString());
	}
}
