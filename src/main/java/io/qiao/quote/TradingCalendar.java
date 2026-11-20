package io.qiao.quote;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判斷某一天台股有沒有開盤：週末一定休市；平日再看證交所的「市場開休市日期」。
 *
 * 休市日清單的來源，依序嘗試：
 * 1. 證交所官方 API（可用 HOLIDAY_API_ENABLED=false 關閉，關閉後不會對外連線）
 *    GET {主機}/rwd/zh/holidaySchedule/holidaySchedule?date=20260101&response=json
 * 2. 程式內附的備援檔 classpath:holidays/{年份}.json（官方原始回應，見 resources/holidays/README.md）
 * 3. 都沒有 → 一律當成「平日都有開盤」，排程照常更新（退回「一直查到拿到當天收盤價」的做法）
 *
 * 這支 API 不是題目指定的，只是「輔助」：考官的測試伺服器很可能沒有它，所以網址預設獨立於 TWSE_BASE_URL，
 * 並有內附檔與「平日都開盤」兩層退路，任何情況都不影響主要功能。
 * 颱風假等臨時休市不在清單裡，遇到時照常查詢。
 *
 * 回應格式的注意事項（實測發現）：
 * 1. 清單裡「也有交易日」：例如「國曆新年開始交易日」、「農曆春節前最後交易日」都是有開盤的日子。
 *    → 名稱含「交易日」的是開盤日，其他（含「市場無交易，僅辦理結算交割作業」）才是休市。
 * 2. 不帶 date 參數時回傳的是「明年」的資料，所以一定要帶 date=年份0101。
 * 3. 日期是西元 yyyy-MM-dd（和個股日成交資訊的民國年不同）。
 */
public class TradingCalendar {

	private static final Logger log = LoggerFactory.getLogger(TradingCalendar.class);

	/** 官方 API 查詢失敗、也沒有內附檔時，隔多久再試一次（避免每一輪都去打） */
	private static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final boolean apiEnabled;

	/** 每一年的休市日（取得成功才會放進來，取得一次就夠用一整年） */
	private final Map<Integer, Set<LocalDate>> closedDaysByYear = new ConcurrentHashMap<>();
	/** 內附檔最早可能的年份 = 交易日期的下限（TradeService.EARLIEST_DATE）；實際有檔案的是 2021 起 */
	private static final int FIRST_BUNDLED_YEAR = 1990;

	/** 內附檔讀過一次就記住（knownClosedDays 用）；volatile：其他執行緒讀得到讀好的結果 */
	private volatile Map<Integer, Set<LocalDate>> bundled;

	/** 每一年最後一次「完全取不到」的時間 */
	private final Map<Integer, Instant> failedAtByYear = new ConcurrentHashMap<>();

	/**
	 * @param apiEnabled false = 不查官方 API（不對外連線），只用內附檔
	 */
	public TradingCalendar(RestClient restClient, ObjectMapper objectMapper, Clock clock, boolean apiEnabled) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.apiEnabled = apiEnabled;
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
		// 取不到休市日清單時是空集合 → 平日一律當成開盤日
		return !closedDays(date.getYear(), throttle).contains(date);
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

		Optional<Set<LocalDate>> closedDays = fromApi(year, throttle).or(() -> fromBundledFile(year));
		if (closedDays.isPresent()) {
			closedDaysByYear.put(year, closedDays.get());
			failedAtByYear.remove(year);
			return closedDays.get();
		}
		failedAtByYear.put(year, Instant.now(clock));
		log.warn("{} 年休市日：官方查不到、也沒有內附檔，暫時當成平日都有開盤", year);
		return Set.of();
	}

	/**
	 * 目前已知的休市日（2.0 網頁提醒「這天可能不是交易日」用，第 6 題 C8-5 a）：
	 * 內附檔的年份（2021～2027）＋排程已經從官方取得的年份（同一年以官方為準）。
	 *
	 * **不會向官方查詢**：開頁時官方慢或擋下也不影響頁面（C8-5 a）。
	 * 不在結果裡的年份 = 沒有資料，網頁只能檢查週末。
	 *
	 * @return 年份 → 休市日（依年份排序）
	 */
	public Map<Integer, Set<LocalDate>> knownClosedDays() {
		Map<Integer, Set<LocalDate>> result = new TreeMap<>(bundledClosedDays());
		result.putAll(closedDaysByYear); // 排程從官方取得的（或排程當時讀的內附檔）優先
		return result;
	}

	/** 內附檔全部讀一次就記住（檔案在程式裡，執行中不會變）；1990（交易日期下限）～明年 */
	private Map<Integer, Set<LocalDate>> bundledClosedDays() {
		Map<Integer, Set<LocalDate>> loaded = bundled;
		if (loaded == null) {
			// 兩個請求同時第一次讀取，頂多各讀一次，結果相同，不需要加鎖
			Map<Integer, Set<LocalDate>> years = new TreeMap<>();
			int lastYear = LocalDate.now(clock).getYear() + 1;
			for (int year = FIRST_BUNDLED_YEAR; year <= lastYear; year++) {
				int y = year;
				fromBundledFile(year).ifPresent(days -> years.put(y, days));
			}
			loaded = Map.copyOf(years);
			bundled = loaded;
		}
		return loaded;
	}

	/** 1. 官方 API */
	private Optional<Set<LocalDate>> fromApi(int year, RequestThrottle throttle) {
		if (!apiEnabled) {
			return Optional.empty();
		}
		try {
			throttle.beforeRequest();
			byte[] body = restClient.get()
					.uri(uri -> uri.path("/rwd/zh/holidaySchedule/holidaySchedule")
							.queryParam("date", year + "0101")
							.queryParam("response", "json")
							.build())
					.retrieve()
					.body(byte[].class);
			Set<LocalDate> closedDays = parse(objectMapper.readTree(body));
			log.info("已從官方取得 {} 年休市日 {} 天", year, closedDays.size());
			return Optional.of(closedDays);
		} catch (IOException | RuntimeException e) {
			// RuntimeException 包含 RestClientException（連不上、404 等）與格式錯誤；這支是輔助 API，失敗就改用內附檔
			log.warn("官方 {} 年休市日查詢失敗，改用內附檔：{}", year, e.getMessage());
			return Optional.empty();
		}
	}

	/** 2. 內附的備援檔（resources/holidays/{年份}.json，官方原始回應） */
	private Optional<Set<LocalDate>> fromBundledFile(int year) {
		ClassPathResource file = new ClassPathResource("holidays/" + year + ".json");
		if (!file.exists()) {
			return Optional.empty();
		}
		try (InputStream in = file.getInputStream()) {
			Set<LocalDate> closedDays = parse(objectMapper.readTree(in));
			log.info("使用內附檔的 {} 年休市日 {} 天", year, closedDays.size());
			return Optional.of(closedDays);
		} catch (IOException | RuntimeException e) {
			log.warn("內附檔 holidays/{}.json 讀取失敗：{}", year, e.getMessage());
			return Optional.empty();
		}
	}

	/** 官方 API 與內附檔格式相同，共用同一套解析 */
	private Set<LocalDate> parse(JsonNode root) {
		if (!"ok".equalsIgnoreCase(root.path("stat").asText())) {
			throw new IllegalStateException("休市日回應狀態異常：" + root.path("stat").asText());
		}
		// 官方對沒有資料的年份（2020 以前、還沒公布的年份）也回 stat ok，只是 data 是空的（2026-11-21 實測）。
		// 每一年至少有元旦等假日，空的代表「沒有資料」而不是「沒有休市日」→ 當成取不到（改用下一個來源）
		if (root.path("data").isEmpty()) {
			throw new IllegalStateException("沒有這一年的資料（data 是空的）");
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
