package io.qiao.exception;

/**
 * 交易的股數、價格、日期不合規則（2.0 第 6 題 C3；API 的輸入檢查在 C6 也會先擋，這裡是服務層的最後把關）。
 *
 * @see io.qiao.service.TradeService 規則
 */
public class InvalidTradeException extends RuntimeException {

	/** 哪一個欄位（shares、price、trade_date），讓 API 和網頁把錯誤顯示在對的欄位旁 */
	private final String field;

	public InvalidTradeException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
