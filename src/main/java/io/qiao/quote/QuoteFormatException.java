package io.qiao.quote;

/**
 * 官方回應的格式和預期不同（缺欄位、日期或價格解析失敗）。
 * 只在 quote 套件內使用，最後會轉成 QuoteUnavailableException（視為「無法判斷」，不能說代號不存在）。
 */
class QuoteFormatException extends RuntimeException {

	QuoteFormatException(String message) {
		super(message);
	}
}
