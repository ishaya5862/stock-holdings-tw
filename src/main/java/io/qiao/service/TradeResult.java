package io.qiao.service;

import io.qiao.calculation.PositionResult;
import io.qiao.entity.Holding;
import io.qiao.entity.Trade;

/**
 * 交易服務寫入後的結果（2.0 第 6 題 C3）：寫入成功就回傳重算後的持股，App 不用再查一次。
 *
 * 日期是週末或休市日時的提醒（「這天可能不是交易日，確定嗎？」）由前端負責（題目 1 d，使用者決定），
 * 伺服器只檢查硬性規則（不能是未來、不能早於 1990-01-01），週末、休市日都照樣儲存。
 *
 * @param holding        這檔持股（刪掉最後一筆交易時，是已刪除的那一筆）
 * @param trade          新增或修改後的交易；刪除時為 null
 * @param position       重算後的結果；持股已刪除時為 null
 * @param holdingDeleted 刪掉的是最後一筆交易，持股列也一起刪除（C2-1）
 */
public record TradeResult(
		Holding holding,
		Trade trade,
		PositionResult position,
		boolean holdingDeleted) {
}
