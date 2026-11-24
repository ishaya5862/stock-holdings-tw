package io.qiao.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.qiao.service.HoldingService;
import io.qiao.service.PositionQueryService;

/**
 * 等鎖逾時或死結（MySQL 1205、1213）的最後一道保險（朋友回饋 2）：
 * 不論發生在哪裡，API 回 409 HOLDING_BUSY、網頁回清單並提示，不會變成 500 或錯誤頁。
 * 用假的服務直接丟出例外（真的鎖要在 MySQL 才測得到，已用腳本驗證）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LockFailureHandlingTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PositionQueryService positionQueryService;

	@MockitoBean
	private HoldingService holdingService;

	@Test
	void API_等鎖逾時_409請使用者過幾秒再試() throws Exception {
		when(positionQueryService.findAll()).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

		// 預期值寫成文字（不用常數）：訊息被不小心改掉時測試會抓到
		mockMvc.perform(get("/api/v2/holdings"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("HOLDING_BUSY"))
				.andExpect(jsonPath("$.error.message").value("另一個裝置正在修改這一檔，請過幾秒再試一次"));
	}

	@Test
	void 第一版網頁_等鎖逾時_回清單並提示() throws Exception {
		when(holdingService.findViewById(1L)).thenThrow(new CannotAcquireLockException("Deadlock found"));

		mockMvc.perform(get("/holdings/{id}/edit", 1))
				.andExpect(redirectedUrl("/holdings"))
				.andExpect(flash().attribute("errorMessage", "另一個裝置正在修改這一檔，請過幾秒再試一次"));
	}
}
