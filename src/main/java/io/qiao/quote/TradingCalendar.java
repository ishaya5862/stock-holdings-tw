package io.qiao.quote;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判斷某一天台股有沒有開盤：週末一定休市；平日再查證交所的「市場開休市日期」。
 *
 * 請求：GET {主機}/rwd/zh/holidaySchedule/holidaySchedule?date=20260101&response=json
 * 回應（節錄）：{"stat":"ok","fields":["日期","名稱","說明"],
 *               "data":[["2026-01-01","中華民國開國紀念日",...],["2026-01-02","國曆新年開始交易日",...]]}
 *
 * 注意（實測發現）：
 * 1. 清單裡「也有交易日」：例如「國曆新年開始交易日」、「農曆春節前最後交易日」都是有開盤的日子。
 *    → 名稱含「交易日」的是開盤日，其他（含「市場無交易，僅辦理結算交割作業」）才是休市。
 * 2. 不帶 date 參數時回傳的是「明年」的資料，所以一定要帶 date=年份0101。
 * 3. 日期是西元 yyyy-MM-dd（和個股日成交資訊的民國年不同）。
 *
 * 這支 API 不是題目指定的，只是「輔助」：查不到（例如考官的測試伺服器沒有這支 API）時，
 * 一律當成「平日都有開盤」，排程就照常更新（退回原本「一直查到拿到當天收盤價」的做法），不影響主要功能。
 * 颱風假等臨時休市不在這份清單裡，也是同樣退回照常查詢。
 */
public class TradingCalendar {

	private static final Logger log = LoggerFactory.getLogger(TradingCalendar.class);

	/** 查詢失敗後，隔多久再試一次（避免每一輪都去打） */
	private static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	/** 每一年的休市日（查成功才會放進來，查一次就夠用一整年） */
	private final Map<Integer, Set<LocalDate>> closedDaysByYear = new ConcurrentHashMap<>();
	/** 每一年最後一次查詢失敗的時間 */
	private final Map<Integer, Instant> failedAtByYear = new ConcurrentHashMap<>();

	public TradingCalendar(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * 這一天有沒有開盤。
	 *
	 * @param throttle 需要向官方查詢時，用來控制請求間隔
	 */
	public boolean isTradingDay(LocalDate date, RequestThrottle throttle) {
		DayOfWeek day = date.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return false;
		}
		Set<LocalDate> closedDays = closedDays(date.getYear(), throttle);
		// 查不到休市日清單時是空集合 → 平日一律當成開盤日
		return !closedDays.contains(date);
	}

	private Set<LocalDate> closedDays(int year, RequestThrottle throttle) {
		Set<LocalDate> cached = closedDaysByYear.get(year);
		if (cached != null) {
			return cached;
		}
		Instant failedAt = failedAtByYear.get(year);
		if (failedAt != null && Instant.now(clock).isBefore(failedAt.plus(RETRY_AFTER_FAILURE))) {
			return Set.of();
		}

		try {
			throttle.beforeRequest();
			Set<LocalDate> closedDays = fetchClosedDays(year);
			closedDaysByYear.put(year, closedDays);
			failedAtByYear.remove(year);
			log.info("已取得 {} 年休市日 {} 天", year, closedDays.size());
			return closedDays;
		} catch (IOException | RuntimeException e) {
			// RuntimeException 包含 RestClientException（連不上、404 等）與格式錯誤；
			// 這支是輔助 API，任何失敗都只退回「平日都有開盤」，不影響主要功能
			failedAtByYear.put(year, Instant.now(clock));
			log.warn("查詢 {} 年休市日失敗，暫時當成平日都有開盤：{}", year, e.getMessage());
			return Set.of();
		}
	}

	private Set<LocalDate> fetchClosedDays(int year) throws IOException {
		byte[] body = restClient.get()
				.uri(uri -> uri.path("/rwd/zh/holidaySchedule/holidaySchedule")
						.queryParam("date", year + "0101")
						.queryParam("response", "json")
						.build())
				.retrieve()
				.body(byte[].class);
		JsonNode root = objectMapper.readTree(body);
		if (!"ok".equalsIgnoreCase(root.path("stat").asText())) {
			throw new IllegalStateException("休市日回應狀態異常：" + root.path("stat").asText());
		}

		JsonNode fields = root.get("fields");
		int dateColumn = QuoteParsing.columnIndex(fields, "日期");
		int nameColumn = QuoteParsing.columnIndex(fields, "名稱");

		Set<LocalDate> closedDays = new HashSet<>();
		for (JsonNode row : root.get("data")) {
			String name = row.get(nameColumn).asText();
			// 名稱含「交易日」的是開盤日（例：國曆新年開始交易日、農曆春節前最後交易日），其他是休市
			if (!name.contains("交易日")) {
				closedDays.add(LocalDate.parse(row.get(dateColumn).asText().trim()));
			}
		}
		return closedDays;
	}
}
