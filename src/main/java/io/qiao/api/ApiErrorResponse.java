package io.qiao.api;

/**
 * App 1.0 API 規格的錯誤格式：{ "error": { "code": "DUPLICATE_CODE", "message": "2330 已在持股中" } }
 *
 * App 只看得懂這個格式（App 原始碼 Contract.parseError）：
 * 收到其他格式（例如 Spring 預設的錯誤 JSON）一律顯示「資料格式錯誤」。
 * message 會直接顯示給使用者看，所以一律是中文、不含程式內部細節。
 */
public record ApiErrorResponse(Error error) {

	public static ApiErrorResponse of(ApiErrorCode code, String message) {
		return new ApiErrorResponse(new Error(code.name(), message));
	}

	public record Error(String code, String message) {
	}
}
