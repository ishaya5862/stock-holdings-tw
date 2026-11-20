package io.qiao.exception;

/**
 * 修改、刪除交易時，用戶端帶的版本號和資料庫不同：另一個裝置已經改過這一筆（C3-5）。
 * 不能讓後送出的默默蓋掉先送出的（遺失更新）→ 409，請使用者重新整理看到最新內容後再操作。
 */
public class TradeVersionConflictException extends RuntimeException {

	public TradeVersionConflictException(Long id) {
		super("這筆交易已在其他裝置修改過，請重新整理後再操作");
	}
}
