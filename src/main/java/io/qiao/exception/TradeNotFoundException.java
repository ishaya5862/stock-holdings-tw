package io.qiao.exception;

/**
 * 找不到指定 id 的交易（可能已在另一個裝置刪除）。2.0 第 6 題 C3。
 */
public class TradeNotFoundException extends RuntimeException {

	public TradeNotFoundException(Long id) {
		super("找不到這筆交易（id " + id + "），可能已被刪除");
	}
}
