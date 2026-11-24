package io.qiao.exception;

/**
 * 等鎖超過 3 秒：同一檔持股正在被另一個請求寫入（例如手機與電腦同時操作）。2.0 第 6 題 C3-2。
 * 正常情況鎖只會佔幾毫秒，會等到 3 秒代表那邊卡住了；回 409 請稍後再試，避免 App 10 秒逾時後重送、請求越堆越多。
 *
 * 訊息寫出「原因」（優化 v1-friendly ④）：App 1.0 只能顯示錯誤的 message，
 * 原本的「資料正在更新，請稍後再試」使用者不知道為什麼。
 * 「請稍後再試」：和其他訊息（例如官方連不上）用同一種說法（D-49，使用者決定統一；原本寫「請過幾秒再試一次」）。
 */
public class HoldingBusyException extends RuntimeException {

	public static final String USER_MESSAGE = "另一個裝置正在修改這一檔，請稍後再試";

	public HoldingBusyException(Throwable cause) {
		super(USER_MESSAGE, cause);
	}
}
