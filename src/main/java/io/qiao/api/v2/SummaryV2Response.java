package io.qiao.api.v2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.PositionResult;
import io.qiao.service.PositionQueryService.PositionView;

/**
 * App 2.0 的「總計物件」（GET /api/v2/summary）。第 6 題 C5-5 a：規則和 v1 相同，但用精確金額（到分），多了已實現。
 *
 * | 欄位           | 型別        | 說明                                                                            |
 * | total_cost     | 字串        | 持有中（股數 > 0）每檔的持有成本加總；取不到收盤價的也算進來                      |
 * | market_value   | 字串        | 有收盤價的持股市值加總                                                          |
 * | unrealized_pnl | 字串        | 有收盤價的持股未實現損益加總                                                    |
 * | return_pct     | 字串或 null | unrealized_pnl ÷ total_cost × 100，四捨五入到小數第二位；沒有持有中的持股為 null |
 * | realized_pnl   | 字串        | 全部持股（含已全部賣出）的已實現損益加總                                        |
 * | holding_count  | 整數        | 持有中（股數 > 0）的檔數                                                        |
 * | quote_missing  | 整數        | 持有中但取不到收盤價的檔數（不算進市值、未實現，但算進成本）                      |
 * | data_error_count | 整數      | 資料異常（交易時間線不合理、無法計算）的檔數；這幾檔不算進任何金額                |
 */
public record SummaryV2Response(
		@JsonProperty("total_cost") String totalCost,
		@JsonProperty("market_value") String marketValue,
		@JsonProperty("unrealized_pnl") String unrealizedPnl,
		@JsonProperty("return_pct") String returnPct,
		@JsonProperty("realized_pnl") String realizedPnl,
		@JsonProperty("holding_count") int holdingCount,
		@JsonProperty("quote_missing") int quoteMissing,
		@JsonProperty("data_error_count") int dataErrorCount) {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	/** public：新網頁（C8-1 a）的摘要卡也用這個，和 App 2.0 的總計一定相同 */
	public static SummaryV2Response from(List<PositionView> views) {
		BigDecimal totalCost = BigDecimal.ZERO;
		BigDecimal marketValue = BigDecimal.ZERO;
		BigDecimal unrealizedPnl = BigDecimal.ZERO;
		BigDecimal realizedPnl = BigDecimal.ZERO;
		int holdingCount = 0;
		int quoteMissing = 0;
		int dataErrorCount = 0;

		for (PositionView view : views) {
			if (view.hasDataError()) {
				dataErrorCount++; // 資料異常的那一檔不算進任何金額（算不出來），另外計數讓 App 提示使用者
				continue;
			}
			PositionResult position = view.position();
			realizedPnl = realizedPnl.add(position.realizedPnl()); // 已全部賣出的也算
			if (position.shares() == 0) {
				continue;
			}
			holdingCount++;
			totalCost = totalCost.add(position.remainingCost());
			if (position.unrealized() == null) {
				quoteMissing++;
			} else {
				marketValue = marketValue.add(position.unrealized().marketValue());
				unrealizedPnl = unrealizedPnl.add(position.unrealized().pnl());
			}
		}

		// 先乘 100 再除，只捨入一次（和第 4 題相同）
		BigDecimal returnPct = totalCost.signum() == 0
				? null
				: unrealizedPnl.multiply(HUNDRED).divide(totalCost, 2, RoundingMode.HALF_UP);
		return new SummaryV2Response(Money.of(totalCost), Money.of(marketValue), Money.of(unrealizedPnl),
				Money.of(returnPct), Money.of(realizedPnl), holdingCount, quoteMissing, dataErrorCount);
	}
}
