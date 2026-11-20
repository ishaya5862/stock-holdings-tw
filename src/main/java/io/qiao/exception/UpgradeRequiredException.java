package io.qiao.exception;

import java.util.List;

/**
 * 這筆持股在 2.0 有 1.0 表達不了的紀錄（零股、多次買進、賣出），1.0 的修改或刪除會破壞它 → 拒絕，並請使用者更新到 2.0。
 *
 * 2.0 第 6 題 C4-3（修改）、C4-4（刪除）決定；訊息就是給 App 1.0 使用者看的通知（N-a）：
 * App 1.0 會用大字顯示錯誤回應的 message（讀 App 原始碼確認：任何狀態碼只要錯誤格式正確都會顯示）。
 * API 回 409 UPGRADE_REQUIRED；第一版網頁回到清單顯示這段訊息。
 */
public class UpgradeRequiredException extends RuntimeException {

	/**
	 * @param code    股票代號
	 * @param action  被拒絕的動作（「修改」或「刪除」）
	 * @param reasons 1.0 表達不了的原因（例如「含零股 500 股」「有賣出紀錄」）
	 */
	public UpgradeRequiredException(String code, String action, List<String> reasons) {
		super(code + " 在 2.0 " + String.join("、", reasons) + "，App 1.0 無法" + action + "，請更新到 App 2.0 後再操作");
	}
}
