package io.qiao.api.v2;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.TradeLine;
import io.qiao.entity.Trade;
import io.qiao.service.PositionQueryService.PositionView;

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
 * | shares_after   | 整數或 null | 這筆交易之後的持有股數（amount 到 shares_after：持股資料異常時為 null） |
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
		@JsonProperty("shares_after") Long sharesAfter,
		@JsonProperty("version") Long version) {

	/**
	 * 一檔持股的交易明細（API 的 GET /holdings/{code}/trades 與新網頁的明細頁共用，C8-1 a）。
	 * 資料異常、算不出來時照樣列出原始交易，使用者才能找出並修正錯的那一筆（朋友回饋 1-1 a）。
	 */
	public static List<TradeV2Response> listOf(PositionView view) {
		List<Trade> trades = view.trades();
		if (view.hasDataError()) {
			return trades.stream().map(TradeV2Response::raw).toList();
		}
		List<TradeLine> lines = view.position().lines();
		// 兩者都依「交易日期 → id」排序（資料庫查詢與 PositionCalculator 同一個規則），第 i 筆互相對應
		return IntStream.range(0, trades.size())
				.mapToObj(i -> from(trades.get(i), lines.get(i)))
				.toList();
	}

	/**
	 * 持股資料異常（時間線不合理、無法重算）時：只列出原始交易（id、日期、股數、價格、版本號），
	 * 計算出來的欄位都是 null。使用者看得到每一筆，才能修改或刪除錯的那一筆（朋友回饋 1-1 a）。
	 */
	static TradeV2Response raw(Trade trade) {
		return new TradeV2Response(
				trade.getId(),
				trade.getSide().name(),
				trade.getTradeDate(),
				trade.isDateEstimated(),
				trade.getShares(),
				Money.of(trade.getPrice()),
				null, null, null, null, null, null,
				trade.getVersion());
	}

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
