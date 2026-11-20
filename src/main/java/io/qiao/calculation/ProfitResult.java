package io.qiao.calculation;

import java.math.BigDecimal;

/**
 * 一筆持股「假設現在用收盤價全部賣出」的計算結果，包含算出盈虧之前的每個中間值。
 *
 * 回傳中間值的原因：
 * - 總計直接加總（總成本 = 每筆 totalCost、市值 = 每筆 sellAmount），第 5 題 API 的 total_cost、market_value 也一樣。
 * - 測試失敗時看得出是哪一步算錯；回答客服「為什麼漲 10% 只賺 9.36%」也一目了然。
 *
 * 金額一律 BigDecimal（避免 double 的誤差），到 API 輸出時才轉成整數。
 *
 * @param buyAmount  買進金額 = 買進價 × 股數
 * @param buyFee     買進手續費
 * @param sellAmount 賣出金額 = 收盤價 × 股數（也就是市值，不扣費用）
 * @param sellFee    賣出手續費
 * @param tax        證交稅（只有賣出收）
 * @param totalCost  總成本 = 買進金額 + 買進手續費（收益率的分母）
 * @param pnl        盈虧 = 賣出金額 − 賣出手續費 − 證交稅 − 買進金額 − 買進手續費
 * @param returnPct  收益率（%）= 盈虧 ÷ 總成本 × 100，四捨五入到小數第二位
 */
public record ProfitResult(
		BigDecimal buyAmount,
		BigDecimal buyFee,
		BigDecimal sellAmount,
		BigDecimal sellFee,
		BigDecimal tax,
		BigDecimal totalCost,
		BigDecimal pnl,
		BigDecimal returnPct) {
}
