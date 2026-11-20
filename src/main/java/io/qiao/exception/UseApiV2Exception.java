package io.qiao.exception;

/**
 * App 2.0（請求帶 X-Client: app2）呼叫了只給 App 1.0 用的 /api/v1 → 拒絕（M4，防止 App 2.0 團隊誤用）。
 *
 * 為什麼要擋：v1 的 name 可能附加「【請更新 App 2.0：…】」說明、lots 只有整數張（零股被捨去），
 * App 2.0 若誤接 v1 會顯示錯的資料；在開發階段就回錯誤，比上線後才發現好。
 * App 1.0 不會送這個標頭，完全不受影響。
 */
public class UseApiV2Exception extends RuntimeException {

	public UseApiV2Exception() {
		super("App 2.0 請改用 /api/v2（/api/v1 只給已發布的 App 1.0 使用，規格見 API.md）");
	}
}
