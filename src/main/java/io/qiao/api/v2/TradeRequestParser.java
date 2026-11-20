package io.qiao.api.v2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.qiao.calculation.TradeSide;

/**
 * 解析並檢查 v2 寫入 API 的請求內容（第 6 題 C6）。
 *
 * 為什麼自己讀 JSON（JsonNode）而不是讓 Spring 直接轉成物件：
 * 自動轉換遇到第一個錯誤就停，也很難說出「哪個欄位」錯；這裡一次檢查所有欄位，
 * 把每個錯誤的欄位都放進 fields（C6-4 b），前端可以一次把錯誤顯示在各欄位旁。
 *
 * 這裡只檢查「格式」；範圍（股數上限、日期不能晚於今天…）由 TradeService.validate 檢查，規則只有一份。
 *
 * | 欄位       | 規定                                                         |
 * | side       | 字串 "BUY" 或 "SELL"（只有新增時需要）                       |
 * | trade_date | 字串 YYYY-MM-DD                                              |
 * | shares     | JSON 整數                                                    |
 * | price      | 字串，最多兩位小數，例如 "600.50"（C6-6 a：和回應格式一致）    |
 * | version    | JSON 整數（只有修改時需要；刪除用網址參數 ?version=）         |
 */
final class TradeRequestParser {

	private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	private static final Pattern PRICE = Pattern.compile("\\d+(\\.\\d{1,2})?");

	private TradeRequestParser() {
	}

	/** 新增（買進或賣出）的內容 */
	record NewTrade(TradeSide side, LocalDate tradeDate, long shares, BigDecimal price) {
	}

	/** 修改的內容（買賣種類、代號不能改，C3-3） */
	record TradeChange(LocalDate tradeDate, long shares, BigDecimal price, long version) {
	}

	static NewTrade parseNew(JsonNode body) {
		Map<String, String> errors = new LinkedHashMap<>();
		requireObject(body);
		TradeSide side = side(body.get("side"), errors);
		LocalDate tradeDate = date(body.get("trade_date"), errors);
		Long shares = integer(body.get("shares"), "shares", "請輸入股數", "股數必須是整數（例如 300、1500）", errors);
		BigDecimal price = price(body.get("price"), errors);
		throwIfAny(errors);
		return new NewTrade(side, tradeDate, shares, price);
	}

	static TradeChange parseChange(JsonNode body) {
		Map<String, String> errors = new LinkedHashMap<>();
		requireObject(body);
		// 帶了不能改的欄位 → 明確拒絕，不默默忽略（免得 App 以為改成功了）
		if (body.has("side")) {
			errors.put("side", "買賣種類不能修改；要改請刪除這筆後重新記錄");
		}
		if (body.has("code")) {
			errors.put("code", "股票代號不能修改；要改請刪除這筆後重新記錄");
		}
		LocalDate tradeDate = date(body.get("trade_date"), errors);
		Long shares = integer(body.get("shares"), "shares", "請輸入股數", "股數必須是整數（例如 300、1500）", errors);
		BigDecimal price = price(body.get("price"), errors);
		Long version = integer(body.get("version"), "version", "請帶上版本號 version（讀取交易時拿到的值）",
				"版本號 version 必須是整數", errors);
		throwIfAny(errors);
		return new TradeChange(tradeDate, shares, price, version);
	}

	/** 刪除的版本號（網址參數 ?version=3，C6-2 b） */
	static long parseVersionParam(String value) {
		if (value == null || value.isBlank()) {
			throw new RequestFieldsException(Map.of("version", "請帶上版本號，例如 ?version=3（讀取交易時拿到的值）"));
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new RequestFieldsException(Map.of("version", "版本號 version 必須是整數"));
		}
	}

	// ===== 各欄位 =====

	private static void requireObject(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new RequestFieldsException(Map.of("body", "請送出 JSON 物件，例如 {\"side\":\"BUY\",...}"));
		}
	}

	private static TradeSide side(JsonNode node, Map<String, String> errors) {
		if (node == null || node.isNull()) {
			errors.put("side", "請輸入買賣種類（BUY 或 SELL）");
			return null;
		}
		if (node.isTextual() && ("BUY".equals(node.asText()) || "SELL".equals(node.asText()))) {
			return TradeSide.valueOf(node.asText());
		}
		errors.put("side", "買賣種類必須是 BUY 或 SELL");
		return null;
	}

	private static LocalDate date(JsonNode node, Map<String, String> errors) {
		if (node == null || node.isNull()) {
			errors.put("trade_date", "請輸入交易日期（YYYY-MM-DD）");
			return null;
		}
		if (node.isTextual() && DATE.matcher(node.asText()).matches()) {
			try {
				return LocalDate.parse(node.asText());
			} catch (DateTimeParseException e) {
				// 格式對、但日期不存在（例如 2026-02-30），落到下面的錯誤訊息
			}
		}
		errors.put("trade_date", "交易日期必須是有效的 YYYY-MM-DD，例如 2026-11-20");
		return null;
	}

	/** JSON 整數（不接受字串 "300"、小數 1.5、超過 long 範圍的數字） */
	private static Long integer(JsonNode node, String field, String missing, String invalid, Map<String, String> errors) {
		if (node == null || node.isNull()) {
			errors.put(field, missing);
			return null;
		}
		if (node.isIntegralNumber() && node.canConvertToLong()) {
			return node.asLong();
		}
		errors.put(field, invalid);
		return null;
	}

	private static BigDecimal price(JsonNode node, Map<String, String> errors) {
		if (node == null || node.isNull()) {
			errors.put("price", "請輸入價格，例如 \"600.50\"");
			return null;
		}
		if (node.isNumber()) {
			// C6-6 a：請求和回應一樣用字串，避免用戶端用浮點數產生誤差
			errors.put("price", "價格請用字串，例如 \"600.50\"（避免小數誤差）");
			return null;
		}
		if (node.isTextual() && PRICE.matcher(node.asText()).matches()) {
			return new BigDecimal(node.asText());
		}
		errors.put("price", "價格必須是數字字串，最多兩位小數，例如 \"600.50\"");
		return null;
	}

	private static void throwIfAny(Map<String, String> errors) {
		if (!errors.isEmpty()) {
			throw new RequestFieldsException(errors);
		}
	}
}
