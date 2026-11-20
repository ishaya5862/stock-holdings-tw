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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
	void 買進價為0_顯示錯誤() throws Exception {
		mockMvc.perform(post("/holdings").param("code", "2330").param("lots", "1").param("buyPrice", "0"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("買進價必須大於 0")));
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
		assertThat(updated.getLots()).isEqualTo(5);
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
