package io.qiao.api.v2;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.PositionResult;
import io.qiao.calculation.Unrealized;
import io.qiao.service.PositionQueryService.PositionView;

/**
 * App 2.0 的「持股物件」（GET /api/v2/holdings、/api/v2/holdings/{code}）。第 6 題 C5。
 *
 * | 欄位             | 型別        | 說明                                                                           |
 * | id               | 整數        | 持股 id（和 v1 相同，對照用；刪掉最後一筆交易後再買會變，長期記住請用 code）      |
 * | code             | 字串        | 股票代號（網址用它識別一檔持股，C5-1 a）                                       |
 * | name             | 字串或 null | 股票名稱（不會有 v1 的「【請更新 App 2.0】」加註，M1）                          |
 * | market           | 字串或 null | TWSE（上市）或 TPEX（上櫃）                                                    |
 * | shares           | 整數或 null | 目前持有股數；0 = 已全部賣出（清單一律包含，C5-4 a）；資料異常時為 null          |
 * | average_cost     | 字串或 null | 平均成本（含買進手續費）= total_cost ÷ shares，到分；0 股或資料異常為 null      |
 * | total_cost       | 字串或 null | 持有成本（目前持有股票的成本，含買進手續費）；資料異常為 null                    |
 * | realized_pnl     | 字串或 null | 已實現損益（歷次賣出加總）；資料異常為 null                                      |
 * | quote_status     | 字串        | OK／UPDATING（還沒查過）／UNAVAILABLE（官方連不上）／NO_RECENT_TRADE（C5-8）    |
 * | close_price      | 字串或 null | 最近收盤價                                                                     |
 * | price_date       | 字串或 null | 收盤價日期 YYYY-MM-DD                                                          |
 * | last_close_price | 字串或 null | 上次成功取得的收盤價（取不到行情時顯示用）                                      |
 * | last_price_date  | 字串或 null | 上次收盤價的日期                                                               |
 * | market_value     | 字串或 null | 市值 = 收盤價 × 股數；quote_status 不是 OK、0 股或資料異常為 null              |
 * | unrealized_pnl   | 字串或 null | 未實現損益（已扣賣出手續費、證交稅）                                            |
 * | return_pct       | 字串或 null | 未實現收益率（%）= unrealized_pnl ÷ total_cost × 100（C1-1）                   |
 * | data_error       | 字串或 null | 資料異常的原因（交易紀錄的時間線不合理，正常操作不會發生）；正常時為 null。       |
 * |                  |             | 發生時其他持股照常計算，請用交易明細找出並修正那一筆（朋友回饋 1-1 a）            |
 * | version          | 整數        | 持股版本號：這檔有任何交易異動就 +1（C3-9），可用來判斷資料是否變動               |
 *
 * 可為 null 的欄位一定出現（值為 null），和 v1 相同。
 */
public record HoldingV2Response(
		@JsonProperty("id") Long id,
		@JsonProperty("code") String code,
		@JsonProperty("name") String name,
		@JsonProperty("market") String market,
		@JsonProperty("shares") Long shares,
		@JsonProperty("average_cost") String averageCost,
		@JsonProperty("total_cost") String totalCost,
		@JsonProperty("realized_pnl") String realizedPnl,
		@JsonProperty("quote_status") String quoteStatus,
		@JsonProperty("close_price") String closePrice,
		@JsonProperty("price_date") LocalDate priceDate,
		@JsonProperty("last_close_price") String lastClosePrice,
		@JsonProperty("last_price_date") LocalDate lastPriceDate,
		@JsonProperty("market_value") String marketValue,
		@JsonProperty("unrealized_pnl") String unrealizedPnl,
		@JsonProperty("return_pct") String returnPct,
		@JsonProperty("data_error") String dataError,
		@JsonProperty("version") Long version) {

	static HoldingV2Response from(PositionView view) {
		PositionResult position = view.position(); // 資料異常時為 null → 計算出來的欄位都是 null
		Unrealized unrealized = position != null ? position.unrealized() : null;
		boolean hasQuote = view.quote() != null;
		return new HoldingV2Response(
				view.holding().getId(),
				view.holding().getCode(),
				view.name(),
				view.market() != null ? view.market().name() : null,
				position != null ? position.shares() : null,
				position != null ? Money.of(position.averageCost()) : null,
				position != null ? Money.of(position.remainingCost()) : null,
				position != null ? Money.of(position.realizedPnl()) : null,
				view.status().name(),
				hasQuote ? Money.of(view.quote().closePrice()) : null,
				hasQuote ? view.quote().priceDate() : null,
				view.stock() != null ? Money.of(view.stock().getLastClosePrice()) : null,
				view.stock() != null ? view.stock().getLastPriceDate() : null,
				unrealized != null ? Money.of(unrealized.marketValue()) : null,
				unrealized != null ? Money.of(unrealized.pnl()) : null,
				unrealized != null ? Money.of(unrealized.returnPct()) : null,
				view.dataError(),
				view.holding().getVersion());
	}
}
