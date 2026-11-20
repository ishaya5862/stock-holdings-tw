package io.qiao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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

/**
 * 第一版網頁看 2.0 的資料（第 6 題 C4-8：保留第一版網頁、規則同 App 1.0、加 2.0 橫幅；C4-6 網頁附註 a）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebV1With2DataTest {

	private static final String ROUNDING_NOTE = "金額四捨五入到元";

	@Autowired
	private MockMvc mockMvc;

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

	/** 1 張 @600 → 再買 500 股、賣 300 股（剩餘成本 729,036.80，有「分」） */
	private Long createWith2Trades() {
		Long id = holdingService.create("2330", 1, new BigDecimal("600")).getId();
		tradeRepository.save(Trade.create(id, TradeSide.BUY, LocalDate.now(clock), 500, new BigDecimal("620.00"), LocalDateTime.now(clock)));
		tradeRepository.save(Trade.create(id, TradeSide.SELL, LocalDate.now(clock), 300, new BigDecimal("700.00"), LocalDateTime.now(clock)));
		return id;
	}

	@Test
	void 清單頁有2點0橫幅() throws Exception {
		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(Matchers.containsString("持股記帳 2.0 已推出")));
	}

	@Test
	void 一點零的資料_金額沒有分_不顯示捨入附註_也沒有2點0標記() throws Exception {
		holdingService.create("2330", 1, new BigDecimal("600"));

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(Matchers.not(Matchers.containsString(ROUNDING_NOTE))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("有多次買進"))));
	}

	@Test
	void 有零股與賣出_顯示捨入附註與原因標記_張數捨去() throws Exception {
		createWith2Trades();

		mockMvc.perform(get("/holdings"))
				.andExpect(content().string(Matchers.containsString(ROUNDING_NOTE)))
				.andExpect(content().string(Matchers.containsString("含零股 200 股、有多次買進、有賣出紀錄")))
				.andExpect(content().string(Matchers.containsString("606.67"))); // 平均買進價（不含手續費）
	}

	@Test
	void 修改頁_一點零表達不了的持股_回清單並說明原因() throws Exception {
		Long id = createWith2Trades();

		mockMvc.perform(get("/holdings/{id}/edit", id))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("errorMessage", Matchers.containsString("請更新到 App 2.0")));
	}

	@Test
	void 刪除_有多筆交易_回清單並說明原因_資料不刪() throws Exception {
		Long id = createWith2Trades();

		mockMvc.perform(post("/holdings/{id}/delete", id))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("errorMessage", Matchers.containsString("無法刪除")));

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id)).hasSize(3);
	}
}
