package io.qiao.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.PortfolioSummary;

/**
 * App 1.0 API 的「總計物件」（GET /api/v1/summary）：
 *
 * | 欄位          | 說明                                                              |
 * | total_cost    | 每筆（買進金額 ＋ 買進手續費）加總（整數元）                       |
 * | market_value  | 每筆（收盤價 × 股數）加總，不扣費用（整數元）                       |
 * | pnl           | 每筆盈虧加總（整數元）                                            |
 * | return_pct    | pnl ÷ total_cost × 100，四捨五入到小數第二位                       |
 * | quote_missing | 取不到收盤價的筆數；不算進 market_value 和 pnl，但算進 total_cost  |
 *
 * return_pct（第 5 題決定 5A）：照規格公式計算；全部取不到收盤價時是 0.00
 * （App 收到 quote_missing > 0 會自己顯示「N 筆未取得收盤價」）；只有沒有持股（total_cost = 0，不能當除數）時是 null。
 * App 原始碼確認 return_pct 允許 null；其他欄位都不可為 null。
 */
public record SummaryResponse(
		@JsonProperty("total_cost") long totalCost,
		@JsonProperty("market_value") long marketValue,
		@JsonProperty("pnl") long pnl,
		@JsonProperty("return_pct") BigDecimal returnPct,
		@JsonProperty("quote_missing") int quoteMissing) {

	static SummaryResponse from(PortfolioSummary summary) {
		return new SummaryResponse(
				HoldingResponse.toWholeDollars(summary.totalCost()),
				HoldingResponse.toWholeDollars(summary.marketValue()),
				HoldingResponse.toWholeDollars(summary.pnl()),
				summary.returnPct(),
				summary.quoteMissing());
	}
}
