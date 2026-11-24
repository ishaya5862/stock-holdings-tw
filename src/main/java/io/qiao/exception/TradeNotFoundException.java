package io.qiao.exception;

/**
 * 找不到指定 id 的交易（可能已在另一個裝置刪除，或網址的 id 不是數字）。2.0 第 6 題 C3、C6。
 * 訊息不放 id：使用者看不懂 id，App 會直接顯示這段訊息。
 */
public class TradeNotFoundException extends RuntimeException {

	public TradeNotFoundException(Long id) {
		super("找不到這筆交易，可能已在另一個裝置刪除");
	}
}
