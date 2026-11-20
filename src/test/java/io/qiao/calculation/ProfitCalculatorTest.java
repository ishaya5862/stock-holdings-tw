package io.qiao.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 盈虧計算測試（第 4 題）。
 * 先寫測試、再寫計算程式：題目的三個例子是驗收標準，一定要通過。
 * 純計算，不啟動 Spring、不連網。
 */
class ProfitCalculatorTest {

	private final ProfitCalculator calculator = new ProfitCalculator();

	// ===== 題目的三個例子 =====

	@Test
	void 例子1_一般股票_2330_1張_買1000_收盤1100() {
		ProfitResult result = calculator.calculateByLots("2330", 1, new BigDecimal("1000.00"), new BigDecimal("1100.00"));

		// 中間值（手算）：
		// 買進金額 1,000,000；買進手續費 1,000,000 × 0.1425% = 1,425
		// 賣出金額 1,100,000；賣出手續費 1,567.5 → 捨去 1,567；證交稅 0.3% = 3,300
		assertThat(result.buyAmount()).isEqualByComparingTo("1000000");
		assertThat(result.buyFee()).isEqualByComparingTo("1425");
		assertThat(result.sellAmount()).isEqualByComparingTo("1100000");
		assertThat(result.sellFee()).isEqualByComparingTo("1567");
		assertThat(result.tax()).isEqualByComparingTo("3300");
		assertThat(result.totalCost()).isEqualByComparingTo("1001425");

		assertThat(result.pnl()).isEqualByComparingTo("93708");
		assertThat(result.returnPct()).isEqualByComparingTo("9.36");
	}

	@Test
	void 例子2_ETF_0050_2張_買150_收盤145點5() {
		ProfitResult result = calculator.calculateByLots("0050", 2, new BigDecimal("150.00"), new BigDecimal("145.50"));

		// 買進金額 300,000；手續費 427.5 → 427
		// 賣出金額 291,000；手續費 414.675 → 414；ETF 證交稅 0.1% = 291
		assertThat(result.buyFee()).isEqualByComparingTo("427");
		assertThat(result.sellFee()).isEqualByComparingTo("414");
		assertThat(result.tax()).isEqualByComparingTo("291");

		assertThat(result.pnl()).isEqualByComparingTo("-10132");
		// −10,132 ÷ 300,427 × 100 = −3.3725… → 四捨五入 −3.37
		assertThat(result.returnPct()).isEqualByComparingTo("-3.37");
	}

	@Test
	void 例子3_手續費低於20元_2363_1張_買10_收盤10點5() {
		ProfitResult result = calculator.calculateByLots("2363", 1, new BigDecimal("10.00"), new BigDecimal("10.50"));

		// 買進手續費 10,000 × 0.1425% = 14.25 → 14 → 未滿 20，收 20
		// 賣出手續費 10,500 × 0.1425% = 14.96 → 14 → 收 20；證交稅 31.5 → 31
		assertThat(result.buyFee()).isEqualByComparingTo("20");
		assertThat(result.sellFee()).isEqualByComparingTo("20");
		assertThat(result.tax()).isEqualByComparingTo("31");

		assertThat(result.pnl()).isEqualByComparingTo("429");
		assertThat(result.returnPct()).isEqualByComparingTo("4.28");
	}

	// ===== 手續費邊界 =====

	@Test
	void 手續費_計算結果19元_未滿20收20() {
		// 14,030 × 0.1425% = 19.99… → 捨去 19 → 收 20
		assertThat(calculator.fee(new BigDecimal("14030"))).isEqualByComparingTo("20");
	}

	@Test
	void 手續費_計算結果剛好20元多一點_捨去後20() {
		// 14,040 × 0.1425% = 20.007 → 捨去 20
		assertThat(calculator.fee(new BigDecimal("14040"))).isEqualByComparingTo("20");
	}

	@Test
	void 手續費_超過20元_無條件捨去不是四捨五入() {
		// 15,000 × 0.1425% = 21.375 → 21；20,000 × 0.1425% = 28.5 → 28（四捨五入會變 29）
		assertThat(calculator.fee(new BigDecimal("15000"))).isEqualByComparingTo("21");
		assertThat(calculator.fee(new BigDecimal("20000"))).isEqualByComparingTo("28");
	}

	// ===== 證交稅 =====

	@Test
	void 證交稅_一般股票千分之3_無條件捨去() {
		// 10,500 × 0.3% = 31.5 → 31
		assertThat(calculator.tax("2363", new BigDecimal("10500"))).isEqualByComparingTo("31");
	}

	@Test
	void 證交稅_ETF千分之1_無條件捨去() {
		// 145,550 × 0.1% = 145.55 → 145
		assertThat(calculator.tax("0050", new BigDecimal("145550"))).isEqualByComparingTo("145");
	}

	@Test
	void ETF判斷_代號00開頭() {
		assertThat(calculator.isEtf("0050")).isTrue();
		assertThat(calculator.isEtf("00878")).isTrue();
		assertThat(calculator.isEtf("006208")).isTrue();
		assertThat(calculator.isEtf("2330")).isFalse();
		assertThat(calculator.isEtf("6488")).isFalse();
	}

	// ===== 總成本（取不到收盤價時，總計仍要算進總成本） =====

	@Test
	void 總成本_買進金額加買進手續費_不需要收盤價() {
		// 例子 1：1,000,000 + 1,425；例子 3：10,000 + 20（最低手續費）
		assertThat(calculator.totalCostByLots(1, new BigDecimal("1000.00"))).isEqualByComparingTo("1001425");
		assertThat(calculator.totalCostByLots(1, new BigDecimal("10.00"))).isEqualByComparingTo("10020");
	}

	@Test
	void 總成本和完整計算的總成本一致() {
		ProfitResult result = calculator.calculateByLots("0050", 2, new BigDecimal("150.00"), new BigDecimal("145.50"));

		assertThat(calculator.totalCostByLots(2, new BigDecimal("150.00"))).isEqualByComparingTo(result.totalCost());
	}

	// ===== 張數轉接方法與輸入檢查 =====

	@Test
	void 用張數呼叫和用股數呼叫結果相同() {
		ProfitResult byLots = calculator.calculateByLots("2330", 3, new BigDecimal("980.50"), new BigDecimal("1005.00"));
		ProfitResult byShares = calculator.calculate("2330", 3000, new BigDecimal("980.50"), new BigDecimal("1005.00"));

		assertThat(byLots).isEqualTo(byShares);
	}

	@Test
	void 股數必須大於0() {
		assertThatThrownBy(() -> calculator.calculate("2330", 0, BigDecimal.TEN, BigDecimal.TEN))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 價格必須大於0() {
		assertThatThrownBy(() -> calculator.calculate("2330", 1000, BigDecimal.ZERO, BigDecimal.TEN))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330", 1000, BigDecimal.TEN, null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
