package io.qiao.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 總計測試（頁面最上方的總計、第 5 題 /api/v1/summary）。
 *
 * 規則（App 1.0 API 規格「總計物件」）：
 * | total_cost    | 每筆（買進金額 ＋ 買進手續費）加總                                  |
 * | market_value  | 每筆（收盤價 × 股數）加總，不扣費用                                 |
 * | pnl           | 每筆盈虧加總                                                        |
 * | return_pct    | pnl ÷ total_cost × 100，四捨五入到小數第二位                         |
 * | quote_missing | 取不到收盤價的筆數；這幾筆不算進 market_value 和 pnl，但算進 total_cost |
 */
class PortfolioSummaryTest {

	private final ProfitCalculator calculator = new ProfitCalculator();

	/** 題目例子 1：2330，1 張，買 1000，收盤 1100 */
	private PortfolioSummary.Line example1() {
		return line("2330", 1, "1000.00", "1100.00");
	}

	/** 題目例子 2：0050，2 張，買 150，收盤 145.5 */
	private PortfolioSummary.Line example2() {
		return line("0050", 2, "150.00", "145.50");
	}

	@Test
	void 規格範例_例子1加例子2() {
		PortfolioSummary summary = PortfolioSummary.of(List.of(example1(), example2()));

		// 規格的總計物件範例剛好是例子 1 + 例子 2：
		// total_cost 1,001,425 + 300,427 = 1,301,852；market_value 1,100,000 + 291,000 = 1,391,000
		// pnl 93,708 − 10,132 = 83,576；return_pct 83,576 ÷ 1,301,852 × 100 = 6.4198… → 6.42
		assertThat(summary.totalCost()).isEqualByComparingTo("1301852");
		assertThat(summary.marketValue()).isEqualByComparingTo("1391000");
		assertThat(summary.pnl()).isEqualByComparingTo("83576");
		assertThat(summary.returnPct()).isEqualByComparingTo("6.42");
		assertThat(summary.quoteMissing()).isZero();
		assertThat(summary.holdingCount()).isEqualTo(2);
	}

	@Test
	void 總收益率是重新計算_不是把每筆收益率平均() {
		PortfolioSummary summary = PortfolioSummary.of(List.of(example1(), example2()));

		// 平均的話是 (9.36 + (−3.37)) ÷ 2 = 2.995，和正確答案 6.42 差很多
		assertThat(summary.returnPct()).isNotEqualByComparingTo("2.995");
		assertThat(summary.returnPct()).isEqualByComparingTo("6.42");
	}

	@Test
	void 部分取不到收盤價_算進總成本_不算進市值和盈虧() {
		PortfolioSummary.Line missing = missingLine(2, "150.00"); // 例子 2 的持股，但取不到收盤價
		PortfolioSummary summary = PortfolioSummary.of(List.of(example1(), missing));

		assertThat(summary.totalCost()).isEqualByComparingTo("1301852"); // 兩筆都算
		assertThat(summary.marketValue()).isEqualByComparingTo("1100000"); // 只有例子 1
		assertThat(summary.pnl()).isEqualByComparingTo("93708");           // 只有例子 1
		// 93,708 ÷ 1,301,852 × 100 = 7.198… → 7.20（分母有、分子沒有，照規格計算）
		assertThat(summary.returnPct()).isEqualByComparingTo("7.20");
		assertThat(summary.quoteMissing()).isEqualTo(1);
		assertThat(summary.someQuotesMissing()).isTrue();
		assertThat(summary.allQuotesMissing()).isFalse();
	}

	@Test
	void 全部取不到收盤價_照規格算出0_但標記為全部未取得() {
		PortfolioSummary summary = PortfolioSummary.of(List.of(missingLine(1, "1000.00"), missingLine(2, "150.00")));

		assertThat(summary.totalCost()).isEqualByComparingTo("1301852");
		assertThat(summary.marketValue()).isEqualByComparingTo("0");
		assertThat(summary.pnl()).isEqualByComparingTo("0");
		assertThat(summary.returnPct()).isEqualByComparingTo("0.00");
		assertThat(summary.quoteMissing()).isEqualTo(2);
		// 畫面用這個判斷：顯示「尚未取得收盤價，暫時無法計算」，而不是誤導人的 0.00%
		assertThat(summary.allQuotesMissing()).isTrue();
	}

	@Test
	void 沒有持股_總成本0_收益率無法計算() {
		PortfolioSummary summary = PortfolioSummary.of(List.of());

		assertThat(summary.totalCost()).isEqualByComparingTo("0");
		assertThat(summary.marketValue()).isEqualByComparingTo("0");
		assertThat(summary.pnl()).isEqualByComparingTo("0");
		assertThat(summary.returnPct()).isNull(); // 0 不能當除數
		assertThat(summary.quoteMissing()).isZero();
		assertThat(summary.hasHoldings()).isFalse();
		assertThat(summary.allQuotesMissing()).isFalse(); // 沒有持股不算「全部未取得」
	}

	// ---- 輔助 ----

	private PortfolioSummary.Line line(String code, int lots, String buyPrice, String closePrice) {
		ProfitResult result = calculator.calculateByLots(code, lots, new BigDecimal(buyPrice), new BigDecimal(closePrice));
		return PortfolioSummary.Line.withProfit(result);
	}

	private PortfolioSummary.Line missingLine(int lots, String buyPrice) {
		return PortfolioSummary.Line.quoteMissing(calculator.totalCostByLots(lots, new BigDecimal(buyPrice)));
	}
}
