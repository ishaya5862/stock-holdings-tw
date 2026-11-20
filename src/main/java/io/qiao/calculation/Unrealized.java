package io.qiao.calculation;

import java.math.BigDecimal;

/**
 * 未實現損益：假設現在用收盤價把「剩下的股票」全部賣出，淨賺多少（規則照舊沿用第 4 題）。
 *
 * @param marketValue 市值 = 收盤價 × 持有股數（不扣費用）
 * @param sellFee     賣出手續費
 * @param tax         證交稅
 * @param pnl         未實現損益 = 市值 − 賣出手續費 − 證交稅 − 剩餘成本
 * @param returnPct   收益率（%）= 未實現損益 ÷ 剩餘成本 × 100，四捨五入到小數第二位（C1-1：分母是剩餘成本）
 */
public record Unrealized(
		BigDecimal marketValue,
		BigDecimal sellFee,
		BigDecimal tax,
		BigDecimal pnl,
		BigDecimal returnPct) {
}
