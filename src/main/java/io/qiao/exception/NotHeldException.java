package io.qiao.exception;

/**
 * 目前沒有持有這個代號（從沒買過，或最後一筆交易已刪除）。2.0 第 6 題 C3（賣出）、C5（查詢）。
 * API 回 404 NOT_HELD。
 */
public class NotHeldException extends RuntimeException {

	public NotHeldException(String code) {
		super("目前沒有持有 " + code);
	}
}
