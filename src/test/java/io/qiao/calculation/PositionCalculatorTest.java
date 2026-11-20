package io.qiao.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 2.0 持股計算測試（第 6 題 C1）：依交易日期重算股數、平均成本、已實現與未實現損益。
 * 先寫測試、再寫計算程式；預期值都是手算（算式寫在註解），不是照程式的輸出抄。
 * 純計算，不啟動 Spring、不連網、不碰資料庫。
 *
 * 規則（PARTB_NOTES B-1、B-8、B-9、B-10）：
 * - 移動平均成本法；每筆買進各自算手續費（每筆最低 20 元）。
 * - 賣出分攤成本 = 剩餘成本 × 賣出股數 ÷ 持有股數，四捨五入到分；全部賣出時 = 剩餘成本全部。
 * - 剩餘成本 = 總成本 − 分攤掉的成本（不用「股數 × 平均成本」，避免零頭累積）。
 * - 排序：交易日期 → id（同一天依輸入順序）；還沒存檔的新交易（id = null）排在同一天最後。
 * - 任何時間點股數都不能是負的（賣超）。
 * - 未實現收益率的分母 = 剩餘成本（照舊沿用第 4 題的公式，只是範圍換成剩下的股票）。
 */
class PositionCalculatorTest {

	private final ProfitCalculator profitCalculator = new ProfitCalculator();
	private final PositionCalculator calculator = new PositionCalculator(profitCalculator);

	private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
	private static final LocalDate D2 = LocalDate.of(2026, 5, 15);
	private static final LocalDate D3 = LocalDate.of(2026, 8, 20);
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

	// ===== 相容：1.0 轉過來的持股（只有一筆買進）結果必須和第 4 題完全相同 =====

	@Test
	void 只有一筆買進_未實現損益與收益率和第4題ProfitCalculator完全相同() {
		// 題目第 4 題的三個例子：App 1.0 看到的數字不能因為升級 2.0 而改變
		Object[][] examples = {
				{ "2330", 1000L, "1000.00", "1100.00", "93708", "9.36" },
				{ "0050", 2000L, "150.00", "145.50", "-10132", "-3.37" },
				{ "2363", 1000L, "10.00", "10.50", "429", "4.28" },
		};
		for (Object[] e : examples) {
			String code = (String) e[0];
			long shares = (Long) e[1];
			BigDecimal price = new BigDecimal((String) e[2]);
			BigDecimal close = new BigDecimal((String) e[3]);

			PositionResult result = calculator.calculate(code, List.of(buy(1L, D1, shares, price.toPlainString())), close, TODAY);
			ProfitResult old = profitCalculator.calculate(code, shares, price, close);

			assertThat(result.remainingCost()).as(code).isEqualByComparingTo(old.totalCost());
			assertThat(result.unrealized().pnl()).as(code).isEqualByComparingTo((String) e[4]);
			assertThat(result.unrealized().pnl()).as(code).isEqualByComparingTo(old.pnl());
			assertThat(result.unrealized().returnPct()).as(code).isEqualByComparingTo((String) e[5]);
			assertThat(result.unrealized().marketValue()).as(code).isEqualByComparingTo(old.sellAmount());
		}
	}

	// ===== 討論時用的完整範例（台積電：兩次買進、賣出一部分） =====

	@Test
	void 台積電範例_兩次買進_賣出一部分_算出已實現與未實現() {
		List<TradeInput> trades = List.of(
				buy(1L, D1, 300, "600.00"),   // 180,000 + 手續費 256（256.5 捨去）= 180,256
				buy(2L, D2, 500, "620.00"),   // 310,000 + 手續費 441（441.75 捨去）= 310,441
				sell(3L, D3, 200, "700.00")); // 140,000 − 手續費 199（199.5 捨去）− 證交稅 420

		PositionResult result = calculator.calculate("2330", trades, new BigDecimal("650.00"), TODAY);

		// 買進後：800 股、總成本 490,697 → 平均成本 613.37125
		TradeLine afterBuys = result.lines().get(1);
		assertThat(afterBuys.sharesAfter()).isEqualTo(800);
		assertThat(afterBuys.remainingCostAfter()).isEqualByComparingTo("490697");

		// 賣出 200 股：分攤成本 490,697 × 200 ÷ 800 = 122,674.25；已實現 139,381 − 122,674.25 = 16,706.75
		TradeLine sellLine = result.lines().get(2);
		assertThat(sellLine.fee()).isEqualByComparingTo("199");
		assertThat(sellLine.tax()).isEqualByComparingTo("420");
		assertThat(sellLine.costOfSold()).isEqualByComparingTo("122674.25");
		assertThat(sellLine.realizedPnl()).isEqualByComparingTo("16706.75");
		assertThat(sellLine.sharesAfter()).isEqualTo(600);

		// 剩 600 股、剩餘成本 490,697 − 122,674.25 = 368,022.75；平均成本 613.37125 → 613.37（賣出不改變平均成本）
		assertThat(result.shares()).isEqualTo(600);
		assertThat(result.remainingCost()).isEqualByComparingTo("368022.75");
		assertThat(result.averageCost()).isEqualByComparingTo("613.37");
		assertThat(result.realizedPnl()).isEqualByComparingTo("16706.75");

		// 未實現（收盤 650）：390,000 − 手續費 555（555.75 捨去）− 證交稅 1,170 − 368,022.75 = 20,252.25
		// 收益率 20,252.25 ÷ 368,022.75 × 100 = 5.5030… → 5.50
		Unrealized unrealized = result.unrealized();
		assertThat(unrealized.marketValue()).isEqualByComparingTo("390000");
		assertThat(unrealized.sellFee()).isEqualByComparingTo("555");
		assertThat(unrealized.tax()).isEqualByComparingTo("1170");
		assertThat(unrealized.pnl()).isEqualByComparingTo("20252.25");
		assertThat(unrealized.returnPct()).isEqualByComparingTo("5.50");
	}

	@Test
	void 未實現收益率的分母是剩餘成本_不是歷來全部買進成本() {
		// C1-1 決定：買 1,000 股 @100（成本 100,000 + 142 = 100,142），賣 500 股 → 剩餘成本 50,071
		// 收盤 110：55,000 − 手續費 78（78.375 捨去）− 證交稅 165 − 50,071 = 4,686
		// 4,686 ÷ 50,071 = 9.36%（若用 100,142 當分母會變成 4.68%，低估一半）
		List<TradeInput> trades = List.of(
				buy(1L, D1, 1000, "100.00"),
				sell(2L, D2, 500, "100.00"));

		PositionResult result = calculator.calculate("2330", trades, new BigDecimal("110.00"), TODAY);

		assertThat(result.remainingCost()).isEqualByComparingTo("50071");
		assertThat(result.unrealized().pnl()).isEqualByComparingTo("4686");
		assertThat(result.unrealized().returnPct()).isEqualByComparingTo("9.36");
	}

	// ===== 手續費與金額的「分」 =====

	@Test
	void 零股手續費最低20元_每筆買進各自算() {
		// 300 股 × 10 元 = 3,000；手續費 3,000 × 0.1425% = 4.275 → 4 → 未滿 20，收 20 → 成本 3,020
		// 平均成本 3,020 ÷ 300 = 10.0666… → 10.07
		PositionResult result = calculator.calculate("2363", List.of(buy(1L, D1, 300, "10.00")), null, TODAY);

		assertThat(result.lines().get(0).fee()).isEqualByComparingTo("20");
		assertThat(result.remainingCost()).isEqualByComparingTo("3020");
		assertThat(result.averageCost()).isEqualByComparingTo("10.07");
	}

	@Test
	void 成交金額有分_零股333股乘以10點05() {
		// 333 × 10.05 = 3,346.65（有分）；手續費 4.76… → 4 → 最低 20 → 成本 3,366.65
		// 平均成本 3,366.65 ÷ 333 = 10.1100… → 10.11
		PositionResult result = calculator.calculate("2330", List.of(buy(1L, D1, 333, "10.05")), null, TODAY);

		assertThat(result.lines().get(0).amount()).isEqualByComparingTo("3346.65");
		assertThat(result.remainingCost()).isEqualByComparingTo("3366.65");
		assertThat(result.averageCost()).isEqualByComparingTo("10.11");
	}

	@Test
	void ETF賣出的證交稅是千分之一() {
		// 0050 賣 1,000 股 @150：150,000 × 0.1% = 150（一般股票會是 450）
		List<TradeInput> trades = List.of(buy(1L, D1, 2000, "140.00"), sell(2L, D2, 1000, "150.00"));

		PositionResult result = calculator.calculate("0050", trades, null, TODAY);

		assertThat(result.lines().get(1).tax()).isEqualByComparingTo("150");
	}

	@Test
	void 賣出虧損_已實現損益是負的() {
		// 買 1,000 @100：成本 100,142；全部賣 @90：90,000 − 128（128.25 捨去）− 270 − 100,142 = −10,540
		List<TradeInput> trades = List.of(buy(1L, D1, 1000, "100.00"), sell(2L, D2, 1000, "90.00"));

		PositionResult result = calculator.calculate("2330", trades, null, TODAY);

		assertThat(result.realizedPnl()).isEqualByComparingTo("-10540");
	}

	// ===== 捨入：除不盡、全部賣光 =====

	@Test
	void 除不盡時分攤成本四捨五入到分_剩餘成本用相減() {
		// 100 股 @50 → 5,020；200 股 @25 → 5,020；共 300 股、成本 10,040
		// 賣 100 股：10,040 × 100 ÷ 300 = 3,346.666… → 3,346.67；剩餘 10,040 − 3,346.67 = 6,693.33
		List<TradeInput> trades = List.of(
				buy(1L, D1, 100, "50.00"),
				buy(2L, D1, 200, "25.00"),
				sell(3L, D2, 100, "60.00"));

		PositionResult result = calculator.calculate("2330", trades, null, TODAY);

		assertThat(result.lines().get(2).costOfSold()).isEqualByComparingTo("3346.67");
		assertThat(result.remainingCost()).isEqualByComparingTo("6693.33");
	}

	@Test
	void 全部賣光時剩餘成本剛好歸零_分攤合計等於買進總成本() {
		// 買 3 股 @10：30 + 手續費最低 20 = 50.00
		// 賣 1 股：50 × 1 ÷ 3 = 16.666… → 16.67，剩 33.33
		// 賣 1 股：33.33 × 1 ÷ 2 = 16.665 → 16.67（四捨五入），剩 16.66
		// 賣 1 股（全部）：分攤 = 剩下的全部 16.66，剩 0.00
		// 合計 16.67 + 16.67 + 16.66 = 50.00，一分不差
		List<TradeInput> trades = List.of(
				buy(1L, D1, 3, "10.00"),
				sell(2L, D2, 1, "10.00"),
				sell(3L, D2, 1, "10.00"),
				sell(4L, D3, 1, "10.00"));

		PositionResult result = calculator.calculate("2330", trades, new BigDecimal("12.00"), TODAY);

		assertThat(result.lines().get(1).costOfSold()).isEqualByComparingTo("16.67");
		assertThat(result.lines().get(2).costOfSold()).isEqualByComparingTo("16.67");
		assertThat(result.lines().get(3).costOfSold()).isEqualByComparingTo("16.66");
		BigDecimal totalAllocated = result.lines().stream()
				.filter(line -> line.trade().side() == TradeSide.SELL)
				.map(TradeLine::costOfSold)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(totalAllocated).isEqualByComparingTo("50.00");

		// C1-2 決定：全部賣光 → 股數 0、平均成本與未實現都沒有值（畫面顯示「—」），即使有收盤價
		assertThat(result.shares()).isZero();
		assertThat(result.remainingCost()).isEqualByComparingTo("0");
		assertThat(result.averageCost()).isNull();
		assertThat(result.unrealized()).isNull();
	}

	@Test
	void 賣光後再買_平均成本只看新的買進_已實現損益保留() {
		// 第 4 題例子 3：1,000 股 @10 → 10,020；全部賣 @10.5：10,500 − 20 − 31 − 10,020 = 429
		// 再買 1,000 股 @20：20,000 + 28（28.5 捨去）= 20,028 → 平均成本 20.028 → 20.03
		List<TradeInput> trades = List.of(
				buy(1L, D1, 1000, "10.00"),
				sell(2L, D2, 1000, "10.50"),
				buy(3L, D3, 1000, "20.00"));

		PositionResult result = calculator.calculate("2363", trades, null, TODAY);

		assertThat(result.realizedPnl()).isEqualByComparingTo("429");
		assertThat(result.shares()).isEqualTo(1000);
		assertThat(result.remainingCost()).isEqualByComparingTo("20028");
		assertThat(result.averageCost()).isEqualByComparingTo("20.03");
	}

	// ===== 排序 =====

	@Test
	void 傳入的順序不影響結果_一律依日期再依id排序() {
		List<TradeInput> sorted = List.of(buy(1L, D1, 300, "600.00"), buy(2L, D2, 500, "620.00"), sell(3L, D3, 200, "700.00"));
		List<TradeInput> shuffled = List.of(sell(3L, D3, 200, "700.00"), buy(2L, D2, 500, "620.00"), buy(1L, D1, 300, "600.00"));

		PositionResult a = calculator.calculate("2330", sorted, null, TODAY);
		PositionResult b = calculator.calculate("2330", shuffled, null, TODAY);

		assertThat(b.realizedPnl()).isEqualByComparingTo(a.realizedPnl());
		assertThat(b.remainingCost()).isEqualByComparingTo(a.remainingCost());
		assertThat(b.lines()).extracting(line -> line.trade().id()).containsExactly(1L, 2L, 3L);
	}

	@Test
	void 同一天依輸入順序_先買後賣可以() {
		List<TradeInput> trades = List.of(buy(1L, D1, 100, "10.00"), sell(2L, D1, 100, "11.00"));

		assertThat(calculator.calculate("2330", trades, null, TODAY).shares()).isZero();
	}

	@Test
	void 同一天先輸入賣出_之前沒有持股_是賣超() {
		// id 1 的賣出排在 id 2 的買進前面 → 賣出當下 0 股
		List<TradeInput> trades = List.of(sell(1L, D1, 100, "11.00"), buy(2L, D1, 100, "10.00"));

		assertThatThrownBy(() -> calculator.calculate("2330", trades, null, TODAY))
				.isInstanceOf(OversellException.class)
				.satisfies(e -> {
					OversellException oversell = (OversellException) e;
					assertThat(oversell.trade().id()).isEqualTo(1L);
					assertThat(oversell.heldBefore()).isZero();
				});
	}

	@Test
	void 還沒存檔的新交易_id是null_排在同一天最後() {
		// 已存在：D1 買 100（id 5）；新的賣出（還沒有 id）同一天 → 排在買進後面 → 可以賣
		List<TradeInput> trades = List.of(sell(null, D1, 100, "11.00"), buy(5L, D1, 100, "10.00"));

		PositionResult result = calculator.calculate("2330", trades, null, TODAY);

		assertThat(result.shares()).isZero();
		assertThat(result.lines().get(1).trade().id()).isNull();
	}

	// ===== 賣超 =====

	@Test
	void 第一筆就是賣出_是賣超() {
		assertThatThrownBy(() -> calculator.calculate("2330", List.of(sell(1L, D1, 100, "10.00")), null, TODAY))
				.isInstanceOf(OversellException.class)
				.hasMessageContaining("2026-03-02")
				.hasMessageContaining("賣出 100 股")
				.hasMessageContaining("只持有 0 股");
	}

	@Test
	void 修改舊的買進造成之後的賣出變成賣超_指出是哪一筆() {
		// 原本：買 500、賣 300 → 合法。使用者把那筆買進改成 100 股 → 賣出 300 時只有 100 股
		List<TradeInput> edited = List.of(buy(1L, D1, 100, "10.00"), sell(2L, D2, 300, "11.00"));

		assertThatThrownBy(() -> calculator.calculate("2330", edited, null, TODAY))
				.isInstanceOf(OversellException.class)
				.satisfies(e -> {
					OversellException oversell = (OversellException) e;
					assertThat(oversell.trade().id()).isEqualTo(2L);
					assertThat(oversell.trade().tradeDate()).isEqualTo(D2);
					assertThat(oversell.heldBefore()).isEqualTo(100);
				});
	}

	@Test
	void 剛好賣到0股可以() {
		List<TradeInput> trades = List.of(buy(1L, D1, 500, "10.00"), sell(2L, D2, 500, "11.00"));

		assertThat(calculator.calculate("2330", trades, null, TODAY).shares()).isZero();
	}

	// ===== 大數字、空清單、沒有收盤價、不合法輸入 =====

	@Test
	void 股數加總超過INT上限也不會溢位() {
		// 22 筆 × 99,999,999 股 = 2,199,999,978 股 > INT 上限 2,147,483,647 → 股數用 long
		List<TradeInput> trades = new ArrayList<>();
		for (long i = 1; i <= 22; i++) {
			trades.add(buy(i, D1, 99_999_999L, "1.00"));
		}

		PositionResult result = calculator.calculate("2330", trades, null, TODAY);

		assertThat(result.shares()).isEqualTo(2_199_999_978L).isGreaterThan(Integer.MAX_VALUE);
	}

	@Test
	void 沒有交易_股數與成本都是0() {
		PositionResult result = calculator.calculate("2330", List.of(), new BigDecimal("650.00"), TODAY);

		assertThat(result.shares()).isZero();
		assertThat(result.remainingCost()).isEqualByComparingTo("0");
		assertThat(result.realizedPnl()).isEqualByComparingTo("0");
		assertThat(result.averageCost()).isNull();
		assertThat(result.unrealized()).isNull();
		assertThat(result.lines()).isEmpty();
	}

	@Test
	void 沒有收盤價_未實現是null_股數平均成本已實現照算() {
		// 和 1.0 的 quote_missing 一致：取不到收盤價時只有未實現算不出來
		List<TradeInput> trades = List.of(buy(1L, D1, 300, "600.00"), buy(2L, D2, 500, "620.00"), sell(3L, D3, 200, "700.00"));

		PositionResult result = calculator.calculate("2330", trades, null, TODAY);

		assertThat(result.unrealized()).isNull();
		assertThat(result.shares()).isEqualTo(600);
		assertThat(result.averageCost()).isEqualByComparingTo("613.37");
		assertThat(result.realizedPnl()).isEqualByComparingTo("16706.75");
	}

	@Test
	void 股數或價格不合法_丟出錯誤() {
		assertThatThrownBy(() -> calculator.calculate("2330", List.of(buy(1L, D1, 0, "10.00")), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330", List.of(buy(1L, D1, 100, "0")), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330", List.of(buy(1L, D1, -5, "10.00")), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330", List.of(buy(1L, D1, 100, "10.123")), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330",
				List.of(new TradeInput(1L, null, D1, 100, new BigDecimal("10.00"))), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> calculator.calculate("2330",
				List.of(new TradeInput(1L, TradeSide.BUY, null, 100, new BigDecimal("10.00"))), null, TODAY))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 證交稅預留交易日期參數_目前規則不變() {
		// C1-3：第 7 題（債券 ETF 停徵期限）要依日期判斷時，不用再改方法的參數
		BigDecimal amount = new BigDecimal("150000");
		assertThat(profitCalculator.tax("0050", amount, TODAY)).isEqualByComparingTo(profitCalculator.tax("0050", amount));
		assertThat(profitCalculator.tax("2330", amount, TODAY)).isEqualByComparingTo(profitCalculator.tax("2330", amount));
	}

	// ===== 測試資料 =====

	private static TradeInput buy(Long id, LocalDate date, long shares, String price) {
		return new TradeInput(id, TradeSide.BUY, date, shares, new BigDecimal(price));
	}

	private static TradeInput sell(Long id, LocalDate date, long shares, String price) {
		return new TradeInput(id, TradeSide.SELL, date, shares, new BigDecimal(price));
	}
}
