package io.qiao.calculation;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一檔股票依全部交易重算後的結果。
 *
 * @param shares          目前持有股數
 * @param remainingCost   剩餘成本（目前持有股票的成本，含買進手續費，到分）
 * @param remainingAmount 剩餘的買進金額（不含手續費，到分）；分攤方式和剩餘成本相同
 * @param averageCost     平均成本 = 剩餘成本 ÷ 股數，四捨五入到分（只用來顯示）；0 股時為 null（畫面顯示「—」）
 * @param averageBuyPrice 平均買進價 = 剩餘買進金額 ÷ 股數（不含手續費），四捨五入到分；0 股時為 null。
 *                        App 1.0 的 buy_price 用它（C4-2）：只有一筆買進時剛好等於原本的買進價，1.0 使用者看到的數字不變
 * @param realizedPnl     已實現損益（每次賣出加總，包含已全部賣光的部分）
 * @param realizedCost    已實現部分的成本 = 每次賣出的分攤成本加總（含買進手續費，到分）；沒有賣出時是 0。
 *                        總計的已實現收益率要用它（Σ已實現 ÷ Σ分攤成本），不能把各檔的百分比平均
 * @param realizedReturnPct 已實現收益率（%）= realizedPnl ÷ realizedCost × 100，四捨五入到小數第二位；
 *                        沒有賣出過時為 null（0 ÷ 0 沒有意義）
 * @param unrealized      未實現損益；沒有收盤價或 0 股時為 null
 * @param lines           每一筆交易的結果，依計算順序（日期 → id）排列
 */
public record PositionResult(
		long shares,
		BigDecimal remainingCost,
		BigDecimal remainingAmount,
		BigDecimal averageCost,
		BigDecimal averageBuyPrice,
		BigDecimal realizedPnl,
		BigDecimal realizedCost,
		BigDecimal realizedReturnPct,
		Unrealized unrealized,
		List<TradeLine> lines) {
}
