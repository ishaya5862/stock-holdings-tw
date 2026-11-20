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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 休市日行事曆測試（MockRestServiceServer 模擬證交所回應，不連網）。
 * 內附檔 resources/holidays/2026.json 是官方原始回應：2026-01-01 開國紀念日休市、2026-01-02 開始交易。
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

	private TradingCalendar calendar(boolean apiEnabled) {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://holiday.test");
		server = MockRestServiceServer.bindTo(builder).build();
		return new TradingCalendar(builder.build(), new ObjectMapper(), clock, apiEnabled);
	}

	@Test
	void 週末不用查官方_直接是休市() {
		TradingCalendar calendar = calendar(true);

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 21), RequestThrottle.NONE)).isFalse(); // 週六
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 22), RequestThrottle.NONE)).isFalse(); // 週日
		server.verify(); // 沒有發出任何請求
	}

	@Test
	void 官方API_名稱含交易日的是開盤日_其他是休市_同一年只查一次() {
		TradingCalendar calendar = calendar(true);
		server.expect(ExpectedCount.once(), requestTo(startsWith(PATH)))
				.andExpect(queryParam("date", "20260101"))
				.andExpect(queryParam("response", "json"))
				.andRespond(withSuccess(HOLIDAYS_2026, MediaType.APPLICATION_JSON));

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isFalse();   // 開國紀念日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 2), RequestThrottle.NONE)).isTrue();    // 開始交易日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 2, 11), RequestThrottle.NONE)).isTrue();   // 最後交易日
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 2, 12), RequestThrottle.NONE)).isFalse();  // 市場無交易
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 20), RequestThrottle.NONE)).isTrue();  // 一般平日
		server.verify();
	}

	@Test
	void 官方API查不到_例如考官的測試伺服器沒有這支API_改用內附檔() {
		TradingCalendar calendar = calendar(true);
		server.expect(requestTo(startsWith(PATH))).andRespond(withResourceNotFound());

		// 內附的 2026.json：1/1 休市、1/2 開盤
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isFalse();
		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 2), RequestThrottle.NONE)).isTrue();
		server.verify();
	}

	@Test
	void 關閉官方API_不對外連線_直接用內附檔() {
		TradingCalendar calendar = calendar(false);

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isFalse();
		server.verify(); // 沒有發出任何請求
	}

	@Test
	void 官方查不到也沒有內附檔的年份_平日一律當成開盤_一小時內不重查() {
		TradingCalendar calendar = calendar(true);
		server.expect(ExpectedCount.once(), requestTo(startsWith(PATH)))
				.andExpect(queryParam("date", "20300101"))
				.andRespond(withResourceNotFound());

		assertThat(calendar.isTradingDay(LocalDate.of(2030, 1, 1), RequestThrottle.NONE)).isTrue();
		assertThat(calendar.isTradingDay(LocalDate.of(2030, 1, 2), RequestThrottle.NONE)).isTrue();
		server.verify(); // 只查一次
	}

	// ===== 第 6 題 C8：空的清單＝沒有資料、網頁用的已知休市日 =====

	/** 官方對沒有資料的年份（2020 以前、未公布）也回 ok，只是 data 是空的（2026-11-21 實測） */
	private static String emptyYear(int year) {
		return "{\"stat\":\"ok\",\"date\":\"" + year + "0101\",\"fields\":[\"日期\",\"名稱\",\"說明\"],\"data\":[],\"total\":0}";
	}

	@Test
	void 官方回應是空的_當成沒有資料_改用內附檔_不會把元旦當成開盤() {
		TradingCalendar calendar = calendar(true);
		server.expect(requestTo(startsWith(PATH))).andRespond(withSuccess(emptyYear(2026), MediaType.APPLICATION_JSON));

		assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1), RequestThrottle.NONE)).isFalse(); // 內附檔：開國紀念日
		server.verify();
	}

	@Test
	void 官方回應是空的也沒有內附檔_平日當成開盤_而且不會記成這年沒有休市日() {
		TradingCalendar calendar = calendar(true);
		server.expect(ExpectedCount.once(), requestTo(startsWith(PATH)))
				.andRespond(withSuccess(emptyYear(2030), MediaType.APPLICATION_JSON));

		assertThat(calendar.isTradingDay(LocalDate.of(2030, 1, 2), RequestThrottle.NONE)).isTrue();
		assertThat(calendar.knownClosedDays()).doesNotContainKey(2030); // 網頁會顯示「只檢查週末」
		server.verify();
	}

	@Test
	void 網頁用的已知休市日_內附2021到2027_不向官方查詢() {
		TradingCalendar calendar = calendar(true);

		var known = calendar.knownClosedDays();

		assertThat(known.keySet()).containsExactly(2021, 2022, 2023, 2024, 2025, 2026, 2027);
		assertThat(known.get(2025)).contains(LocalDate.of(2025, 1, 28))      // 農曆除夕
				.doesNotContain(LocalDate.of(2025, 1, 22));                  // 春節前最後交易日（有開盤）
		assertThat(known.get(2026)).contains(LocalDate.of(2026, 1, 1));
		server.verify(); // 沒有發出任何請求
	}

	@Test
	void 網頁用的已知休市日_排程從官方取得的年份優先() {
		TradingCalendar calendar = calendar(true);
		server.expect(requestTo(startsWith(PATH))).andRespond(withSuccess(HOLIDAYS_2026, MediaType.APPLICATION_JSON));
		calendar.isTradingDay(LocalDate.of(2026, 11, 20), RequestThrottle.NONE); // 排程查過 2026

		assertThat(calendar.knownClosedDays().get(2026))
				.containsExactlyInAnyOrder(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 12), LocalDate.of(2026, 10, 9));
	}
}
