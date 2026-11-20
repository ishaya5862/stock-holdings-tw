package io.qiao.api.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * v2 寫入（買進、賣出、修改、刪除交易）成功後的回應（第 6 題 C6-3 a）：
 * 一次給「這筆交易＋重算後的持股」，App 不用再查一次（手機網路不穩，少一次請求就少一次逾時重送的機會）。
 *
 * | 欄位            | 型別                | 說明                                                         |
 * | trade           | 交易物件或 null      | 新增或修改後的交易；刪除時為 null                             |
 * | holding         | 持股物件或 null      | 重算後的持股；刪掉最後一筆交易、持股也一起刪除時為 null       |
 * | holding_deleted | 布林                | true = 刪掉的是最後一筆交易，持股也一起刪除（C2-1）            |
 */
public record TradeWriteResponse(
		@JsonProperty("trade") TradeV2Response trade,
		@JsonProperty("holding") HoldingV2Response holding,
		@JsonProperty("holding_deleted") boolean holdingDeleted) {
}
