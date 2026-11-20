package io.qiao.calculation;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一檔股票依全部交易重算後的結果。
 *
 * @param shares        目前持有股數
 * @param remainingCost 剩餘成本（目前持有股票的成本，含買進手續費，到分）
 * @param averageCost   平均成本 = 剩餘成本 ÷ 股數，四捨五入到分（只用來顯示）；0 股時為 null（畫面顯示「—」）
 * @param realizedPnl   已實現損益（每次賣出加總，包含已全部賣光的部分）
 * @param unrealized    未實現損益；沒有收盤價或 0 股時為 null
 * @param lines         每一筆交易的結果，依計算順序（日期 → id）排列
 */
public record PositionResult(
		long shares,
		BigDecimal remainingCost,
		BigDecimal averageCost,
		BigDecimal realizedPnl,
		Unrealized unrealized,
		List<TradeLine> lines) {
}
