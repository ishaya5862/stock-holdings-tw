package io.qiao.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 總計（頁面最上方、第 5 題 /api/v1/summary 共用），規則照 App 1.0 API 規格的「總計物件」：
 *
 * | totalCost    | 每筆（買進金額 ＋ 買進手續費）加總，取不到收盤價的持股「也算」          |
 * | marketValue  | 每筆（收盤價 × 股數）加總，不扣費用；取不到收盤價的持股「不算」          |
 * | pnl          | 每筆盈虧加總；取不到收盤價的持股「不算」                                 |
 * | returnPct    | pnl ÷ totalCost × 100，四捨五入到小數第二位（重新計算，不是平均每筆收益率） |
 * | quoteMissing | 取不到收盤價的筆數                                                       |
 *
 * returnPct 只有在「沒有持股（totalCost = 0，不能當除數）」時是 null。
 * 全部都取不到收盤價時，照規格算出 0.00；畫面再用 allQuotesMissing() 判斷，改顯示說明文字，避免誤以為「不賺不賠」。
 *
 * @param totalCost    總成本
 * @param marketValue  市值
 * @param pnl          盈虧
 * @param returnPct    收益率（%）；沒有持股時為 null
 * @param holdingCount 持股筆數
 * @param quoteMissing 取不到收盤價的筆數
 */
public record PortfolioSummary(
		BigDecimal totalCost,
		BigDecimal marketValue,
		BigDecimal pnl,
		BigDecimal returnPct,
		int holdingCount,
		int quoteMissing) {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	/**
	 * 由每一筆持股的計算結果加總。
	 */
	public static PortfolioSummary of(List<Line> lines) {
		BigDecimal totalCost = BigDecimal.ZERO;
		BigDecimal marketValue = BigDecimal.ZERO;
		BigDecimal pnl = BigDecimal.ZERO;
		int quoteMissing = 0;

		for (Line line : lines) {
			totalCost = totalCost.add(line.totalCost());
			if (line.profit() == null) {
				quoteMissing++;
			} else {
				marketValue = marketValue.add(line.profit().sellAmount());
				pnl = pnl.add(line.profit().pnl());
			}
		}

		BigDecimal returnPct = totalCost.signum() == 0
				? null
				: pnl.multiply(HUNDRED).divide(totalCost, 2, RoundingMode.HALF_UP);

		return new PortfolioSummary(totalCost, marketValue, pnl, returnPct, lines.size(), quoteMissing);
	}

	/** 有沒有持股 */
	public boolean hasHoldings() {
		return holdingCount > 0;
	}

	/** 有持股，但全部都取不到收盤價（收益率會算出 0.00，畫面要改顯示說明文字） */
	public boolean allQuotesMissing() {
		return hasHoldings() && quoteMissing == holdingCount;
	}

	/** 有部分持股取不到收盤價（畫面要加註「N 筆未取得收盤價，未計入市值與盈虧」） */
	public boolean someQuotesMissing() {
		return quoteMissing > 0 && quoteMissing < holdingCount;
	}

	/**
	 * 總計的一筆輸入。
	 *
	 * @param totalCost 這筆的總成本（一定要有，取不到收盤價也要算進總成本）
	 * @param profit    這筆的盈虧計算結果；取不到收盤價時為 null
	 */
	public record Line(BigDecimal totalCost, ProfitResult profit) {

		/** 有收盤價：總成本直接用計算結果裡的 */
		public static Line withProfit(ProfitResult profit) {
			return new Line(profit.totalCost(), profit);
		}

		/** 取不到收盤價：只有總成本 */
		public static Line quoteMissing(BigDecimal totalCost) {
			return new Line(totalCost, null);
		}
	}
}
