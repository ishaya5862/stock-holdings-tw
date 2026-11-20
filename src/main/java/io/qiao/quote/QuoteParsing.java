package io.qiao.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 解析官方回應的共用小工具（證交所、櫃買中心格式相近，共用同一套）。
 */
final class QuoteParsing {

	private QuoteParsing() {
	}

	/**
	 * 依欄位名稱找出它在 fields 陣列中的位置，不寫死「第幾欄」。
	 * 比對前先去掉空白：證交所是「日期」、櫃買中心是「日 期」（中間有空白）。
	 *
	 * @param candidates 可接受的名稱（例：收盤價、收盤）
	 * @throws QuoteFormatException 找不到這個欄位（官方格式改變）
	 */
	static int columnIndex(JsonNode fields, String... candidates) {
		for (int i = 0; i < fields.size(); i++) {
			String fieldName = removeSpaces(fields.get(i).asText());
			for (String candidate : candidates) {
				if (fieldName.equals(candidate)) {
					return i;
				}
			}
		}
		throw new QuoteFormatException("回應中找不到欄位：" + String.join(" / ", candidates));
	}

	/**
	 * 把 data 陣列轉成「每日收盤價」清單，依日期由舊到新排序。
	 * 收盤價是「--」（當天沒有成交）的那幾天直接略過。
	 */
	static List<DailyClose> dailyCloses(JsonNode data, int dateColumn, int closeColumn) {
		List<DailyClose> result = new ArrayList<>();
		for (JsonNode row : data) {
			BigDecimal close = parsePrice(row.get(closeColumn).asText());
			if (close != null) {
				result.add(new DailyClose(parseRocDate(row.get(dateColumn).asText()), close));
			}
		}
		result.sort(Comparator.comparing(DailyClose::date));
		return result;
	}

	/**
	 * 民國年日期轉西元：「115/11/19」→ 2026-11-19（民國年 + 1911 = 西元年）。
	 */
	static LocalDate parseRocDate(String rocDate) {
		String[] parts = removeSpaces(rocDate).split("/");
		if (parts.length != 3) {
			throw new QuoteFormatException("日期格式不正確：" + rocDate);
		}
		try {
			return LocalDate.of(
					Integer.parseInt(parts[0]) + 1911,
					Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (RuntimeException e) {
			throw new QuoteFormatException("日期格式不正確：" + rocDate);
		}
	}

	/**
	 * 價格字串轉數字：去掉千分位逗號（「2,330.00」→ 2330.00）。
	 *
	 * @return 沒有成交（「--」或空白）時回傳 null
	 */
	static BigDecimal parsePrice(String text) {
		String cleaned = removeSpaces(text).replace(",", "");
		if (cleaned.isEmpty() || cleaned.startsWith("--")) {
			return null;
		}
		try {
			return new BigDecimal(cleaned);
		} catch (NumberFormatException e) {
			throw new QuoteFormatException("價格格式不正確：" + text);
		}
	}

	private static String removeSpaces(String text) {
		// \s 不包含全形空白，另外處理
		return text.replaceAll("[\\s　]", "");
	}
}
