package io.qiao.exception;

/**
 * 賣出一檔目前沒有持有的股票（從沒買過）。2.0 第 6 題 C3。
 */
public class NotHeldException extends RuntimeException {

	public NotHeldException(String code) {
		super("目前沒有持有 " + code + "，無法賣出");
	}
}
