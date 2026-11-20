package io.qiao.exception;

/**
 * 官方行情來源無法使用（連不上、逾時、被擋、回應格式不對）。
 *
 * 和「查無此股票」不同：這時候無法確認代號是否存在，
 * 新增持股時回 503 QUOTE_UNAVAILABLE（D-1 決定），清單上收盤價顯示「未取得」。
 */
public class QuoteUnavailableException extends RuntimeException {

	/** 顯示給使用者看的訊息 */
	public static final String USER_MESSAGE = "行情來源暫時無法連線，請稍後再試";

	public QuoteUnavailableException(String detail, Throwable cause) {
		// detail 寫進 log 方便除錯；畫面上一律顯示 USER_MESSAGE，不把內部錯誤細節給使用者看
		super(detail, cause);
	}

	public QuoteUnavailableException(String detail) {
		super(detail);
	}
}
