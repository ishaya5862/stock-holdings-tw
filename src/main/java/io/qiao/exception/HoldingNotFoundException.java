package io.qiao.exception;

/**
 * 找不到指定 id 的持股（可能已被刪除）。
 * 網頁會回到清單並顯示訊息；第 5 題的 API 對應 404 NOT_FOUND。
 */
public class HoldingNotFoundException extends RuntimeException {

	public HoldingNotFoundException(Long id) {
		super("找不到 id " + id + " 的持股");
	}
}
