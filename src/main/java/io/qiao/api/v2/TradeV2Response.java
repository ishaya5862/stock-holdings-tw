package io.qiao.api.v2;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.TradeLine;
import io.qiao.entity.Trade;

/**
 * App 2.0 的「交易物件」（GET /api/v2/holdings/{code}/trades）。第 6 題 C5-6 a。
 * 手續費、證交稅、已實現損益不存在資料庫，每次依交易紀錄重算後附上（計算規則只有後端一份）。
 *
 * | 欄位           | 型別        | 說明                                                                 |
 * | id             | 整數        | 交易 id（修改、刪除用）                                              |
 * | side           | 字串        | BUY（買進）或 SELL（賣出）                                           |
 * | trade_date     | 字串        | 交易日期 YYYY-MM-DD                                                  |
 * | date_estimated | 布林        | true = 日期是推估的（1.0 轉入、App 1.0 新增時沒有日期），請使用者確認 |
 * | shares         | 整數        | 股數                                                                 |
 * | price          | 字串        | 每股價格                                                             |
 * | amount         | 字串        | 成交金額 = 價格 × 股數                                               |
 * | fee            | 字串        | 手續費（0.1425%，捨去到元，最低 20 元）                              |
 * | tax            | 字串或 null | 證交稅（只有賣出有）                                                 |
 * | cost_of_sold   | 字串或 null | 這次賣出分攤掉的成本（移動平均，到分；只有賣出有）                   |
 * | realized_pnl   | 字串或 null | 這次賣出的已實現損益（只有賣出有）                                   |
 * | shares_after   | 整數        | 這筆交易之後的持有股數                                               |
 * | version        | 整數        | 交易版本號：修改、刪除時要帶上（C3-5），和伺服器不同代表另一個裝置改過 |
 *
 * 排列順序：交易日期 → 輸入順序（同一天依輸入的先後），也就是計算平均成本的順序。
 */
public record TradeV2Response(
		@JsonProperty("id") Long id,
		@JsonProperty("side") String side,
		@JsonProperty("trade_date") LocalDate tradeDate,
		@JsonProperty("date_estimated") boolean dateEstimated,
		@JsonProperty("shares") long shares,
		@JsonProperty("price") String price,
		@JsonProperty("amount") String amount,
		@JsonProperty("fee") String fee,
		@JsonProperty("tax") String tax,
		@JsonProperty("cost_of_sold") String costOfSold,
		@JsonProperty("realized_pnl") String realizedPnl,
		@JsonProperty("shares_after") long sharesAfter,
		@JsonProperty("version") Long version) {

	/**
	 * @param trade 資料庫的交易（版本號、是否推估）
	 * @param line  同一筆交易的重算結果（手續費、稅、已實現、交易後股數）
	 */
	static TradeV2Response from(Trade trade, TradeLine line) {
		return new TradeV2Response(
				trade.getId(),
				trade.getSide().name(),
				trade.getTradeDate(),
				trade.isDateEstimated(),
				trade.getShares(),
				Money.of(trade.getPrice()),
				Money.of(line.amount()),
				Money.of(line.fee()),
				Money.of(line.tax()),
				Money.of(line.costOfSold()),
				Money.of(line.realizedPnl()),
				line.sharesAfter(),
				trade.getVersion());
	}
}
