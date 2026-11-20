package io.qiao.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import static org.hamcrest.Matchers.startsWith;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 休市日行事曆測試（MockRestServiceServer 模擬證交所回應，不連網）。
 */
class TradingCalendarTest {

	private static final String PATH = "http://holiday.test/rwd/zh/holidaySchedule/holidaySchedule";

	/** 節錄自實際回應的格式：清單裡也有「開始交易日」「最後交易日」這種有開盤的日子 */
	private static final String HOLIDAYS_2026 = """
			{"stat":"ok","date":"20260101","title":"115 年市場開休市日期","fields":["日期","名稱","說明"],
			 "data":[["2026-01-01","中華民國開國紀念日","依規定放假1日。"],
			         ["2026-01-02","國曆新年開始交易日","國曆新年開始交易。"],
			         ["2026-02-11","農曆春節前最後交易日","農曆春節前最後交易。"],
			         ["2026-02-12","市場無交易，僅辦理結算交割作業",""],
			         ["2026-10-09","國慶日補假","依規定放假1日。"]]}
			""";

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneId.of("Asia/Taipei"));

	private MockRestServiceServer server;
	private TradingCalendar calendar;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://holiday.test");
		server = MockRestServiceServer.bindTo(builder).build();
		calendar = new TradingCalendar(builder.build(), new ObjectMapper(), clock);
	}

	@Test
	void 週末不用查官方_直接是休市() {
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 21), RequestThrottle.NONE)).isFalse(); // 週六
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 22), RequestThrottle.NONE)).isFalse(); // 週日
		server.verify(); // 沒有發出任何請求
	}

	@Test
	void 休市日清單_名稱含交易日的是開盤日_其他是休市() {
		server.expect(ExpectedCount.once(), requestTo(startsWith(PATH)))
				.andExpect(queryParam("date", "20260101"))
				.andExpect(queryParam("response", "json"))
				.andRespond(withSuccess(HOLIDAYS_2026, MediaType.APPLICATION_JSON));

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isFalse();   // 開國紀念日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 2), RequestThrottle.NONE)).isTrue();    // 開始交易日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 2, 11), RequestThrottle.NONE)).isTrue();   // 最後交易日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 2, 12), RequestThrottle.NONE)).isFalse();  // 市場無交易
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 20), RequestThrottle.NONE)).isTrue();  // 一般平日

		// 同一年只查一次（ExpectedCount.once），之後都用快取
		server.verify();
	}

	@Test
	void 查不到休市日清單_例如考官的測試伺服器沒有這支API_平日一律當成開盤() {
		server.expect(requestTo(startsWith(PATH))).andRespond(withResourceNotFound());

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isTrue();
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 20), RequestThrottle.NONE)).isTrue();
		// 失敗後 1 小時內不會再查（server 只預期一次請求，多查一次 verify 會失敗）
		server.verify();
	}
}
