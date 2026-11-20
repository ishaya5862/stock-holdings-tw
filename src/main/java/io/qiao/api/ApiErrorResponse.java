package io.qiao.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * App 1.0 API 規格的錯誤格式：{ "error": { "code": "DUPLICATE_CODE", "message": "2330 已在持股中" } }
 *
 * App 只看得懂這個格式（App 原始碼 Contract.parseError）：
 * 收到其他格式（例如 Spring 預設的錯誤 JSON）一律顯示「資料格式錯誤」。
 * message 會直接顯示給使用者看，所以一律是中文、不含程式內部細節。
 *
 * 2.0（第 6 題 C6）多兩個「可有可無」的欄位，只有 v2 會用到，沒有值時不輸出（v1 的錯誤回應和以前完全相同）：
 * - fields：哪個欄位錯、錯在哪（欄位名 → 訊息，C6-4 b），前端可以把錯誤顯示在對應欄位旁
 * - details：補充資料，例如賣超時的 trade_date（哪一天）、held_before（當時持有多少）（C6-5 a）
 */
public record ApiErrorResponse(Error error) {

	public static ApiErrorResponse of(ApiErrorCode code, String message) {
		return new ApiErrorResponse(new Error(code.name(), message, null, null));
	}

	public static ApiErrorResponse withFields(ApiErrorCode code, String message, Map<String, String> fields) {
		return new ApiErrorResponse(new Error(code.name(), message, fields, null));
	}

	public static ApiErrorResponse withDetails(ApiErrorCode code, String message, Map<String, Object> details) {
		return new ApiErrorResponse(new Error(code.name(), message, null, details));
	}

	public record Error(
			String code,
			String message,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fields,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {
	}
}
