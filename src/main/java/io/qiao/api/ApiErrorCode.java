package io.qiao.api;

import org.springframework.http.HttpStatus;

/**
 * API 錯誤代碼與對應的 HTTP 狀態碼。
 * 前四個是 App 1.0 API 規格列出的；其他是規格沒列、但一定要用同樣格式回應的情況
 * （否則 App 會顯示「資料格式錯誤」）。
 */
public enum ApiErrorCode {

	/** 欄位缺少、型別不對、超出範圍、JSON 格式錯誤（規格） */
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

	/** 找不到持股、網址的 id 不是數字、/api 底下不存在的網址（規格） */
	NOT_FOUND(HttpStatus.NOT_FOUND),

	/** 上市、上櫃都查不到這個代號，或代號格式不可能存在（規格） */
	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND),

	/** 同一個代號已經在持股中（規格） */
	DUPLICATE_CODE(HttpStatus.CONFLICT),

	/** 官方行情連不上，無法確認代號是否存在（D-1 決定：503） */
	QUOTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

	/**
	 * 2.0：這筆持股有零股、多次買進或賣出紀錄，1.0 的修改或刪除會破壞它（C4-3、C4-4）。
	 * 用 409（和目前資料狀態衝突）比 400（輸入錯誤）準確；App 1.0 任何狀態碼都會顯示 message。
	 */
	UPGRADE_REQUIRED(HttpStatus.CONFLICT),

	/** 2.0（v2）：目前沒有持有這個代號（從沒買過，或最後一筆交易已刪除） */
	NOT_HELD(HttpStatus.NOT_FOUND),

	/** 2.0：同一檔持股正在被另一個請求寫入，等鎖超過 3 秒（C3-2）；稍後再試即可 */
	HOLDING_BUSY(HttpStatus.CONFLICT),

	/** 2.0：App 2.0（X-Client: app2）呼叫了 v1，請改用 /api/v2（M4，防止誤用） */
	USE_API_V2(HttpStatus.BAD_REQUEST),

	/** HTTP 方法不支援（例如對 /api/v1/summary 送 POST） */
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

	/** 伺服器內部錯誤（不把程式細節給使用者看） */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ApiErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
