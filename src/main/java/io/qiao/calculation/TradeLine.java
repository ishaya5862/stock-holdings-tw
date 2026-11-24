package io.qiao.calculation;

import java.math.BigDecimal;

/**
 * 重算後每一筆交易的結果（交易明細頁、API 的每一筆交易都用它）。
 *
 * @param trade              原始交易
 * @param amount             成交金額 = 價格 × 股數（零股可能有「分」，例如 333 × 10.05 = 3,346.65）
 * @param fee                手續費（捨去到元，最低 20 元）
 * @param tax                證交稅（只有賣出有；買進為 null）
 * @param costOfSold         這次賣出分攤掉的成本（到分；買進為 null）
 * @param realizedPnl        這次賣出的已實現損益 = 成交金額 − 手續費 − 證交稅 − 分攤成本（買進為 null）
 * @param realizedReturnPct  這次賣出的已實現收益率（%）= 已實現損益 ÷ 分攤成本 × 100，四捨五入到小數第二位（買進為 null）
 * @param sharesAfter        這筆交易之後的持有股數
 * @param remainingCostAfter 這筆交易之後的剩餘成本（含買進手續費）
 */
public record TradeLine(
		TradeInput trade,
		BigDecimal amount,
		BigDecimal fee,
		BigDecimal tax,
		BigDecimal costOfSold,
		BigDecimal realizedPnl,
		BigDecimal realizedReturnPct,
		long sharesAfter,
		BigDecimal remainingCostAfter) {
}
