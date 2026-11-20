package io.qiao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.containsString;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 首頁測試：確認時間是「台北時間」，不是主機時間。
 */
@WebMvcTest(HomeController.class)
@Import(HomeControllerTest.FixedClockConfig.class)
class HomeControllerTest {

	@TestConfiguration
	static class FixedClockConfig {
		// 固定在 UTC 2026-11-20 01:00:00 = 台北時間 09:00:00（台北是 UTC+8）
		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneId.of("Asia/Taipei"));
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 首頁顯示標題與台北時間() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("持股記帳")))
				.andExpect(content().string(containsString("2026-11-20 09:00:00")));
	}
}
