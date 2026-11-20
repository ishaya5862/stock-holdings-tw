package io.qiao.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * 盈虧計算（第 4 題）：假設現在用收盤價全部賣出，淨賺多少。
 *
 * | 項目   | 規則                                                                 |
 * | 手續費 | 成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元；買進、賣出各收一次 |
 * | 證交稅 | 只有賣出收。一般股票 0.3%，ETF（代號 00 開頭）0.1%；無條件捨去到元     |
 * | 盈虧   | 賣出金額 − 賣出手續費 − 證交稅 − 買進金額 − 買進手續費                  |
 * | 收益率 | 盈虧 ÷（買進金額 ＋ 買進手續費）× 100，四捨五入到小數第二位             |
 *
 * 設計：
 * - 不依賴資料庫、不連網，單元測試直接 new 就能測（@Component 只是讓 Spring 可以注入）。
 * - 核心用「股數」計算（Part B 2.0 改用股記帳可直接沿用）；1.0 的「張數」用 calculateByLots 轉接，
 *   計算規則只有一份，改一次兩邊都生效。
 */
@Component
public class ProfitCalculator {

	/** 1 張 = 1,000 股 */
	public static final int SHARES_PER_LOT = 1000;

	/** 手續費率 0.1425% */
	private static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
	/** 手續費每筆最低 20 元 */
	private static final BigDecimal MIN_FEE = new BigDecimal("20");
	/** 證交稅：一般股票 0.3% */
	private static final BigDecimal STOCK_TAX_RATE = new BigDecimal("0.003");
	/** 證交稅：ETF 0.1% */
	private static final BigDecimal ETF_TAX_RATE = new BigDecimal("0.001");

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	/**
	 * 用張數計算（1.0 版使用）：張數 × 1,000 換成股數，再交給核心計算。
	 */
	public ProfitResult calculateByLots(String code, int lots, BigDecimal buyPrice, BigDecimal closePrice) {
		return calculate(code, (long) lots * SHARES_PER_LOT, buyPrice, closePrice);
	}

	/**
	 * 核心計算：用股數。
	 *
	 * @param code       股票代號（判斷是否為 ETF，決定證交稅率）
	 * @param shares     股數
	 * @param buyPrice   買進價（每股）
	 * @param closePrice 收盤價（每股），即假設的賣出價
	 */
	public ProfitResult calculate(String code, long shares, BigDecimal buyPrice, BigDecimal closePrice) {
		if (shares <= 0) {
			throw new IllegalArgumentException("股數必須大於 0：" + shares);
		}
		requirePositive(buyPrice, "買進價");
		requirePositive(closePrice, "收盤價");

		BigDecimal quantity = BigDecimal.valueOf(shares);

		BigDecimal buyAmount = buyPrice.multiply(quantity);
		BigDecimal buyFee = fee(buyAmount);

		BigDecimal sellAmount = closePrice.multiply(quantity);
		BigDecimal sellFee = fee(sellAmount);
		BigDecimal tax = tax(code, sellAmount);

		BigDecimal totalCost = buyAmount.add(buyFee);
		BigDecimal pnl = sellAmount.subtract(sellFee).subtract(tax).subtract(buyAmount).subtract(buyFee);

		// 先乘 100 再除，divide 直接算到小數第二位並四捨五入（只捨入一次，避免兩次捨入的誤差）
		// HALF_UP 對負數一樣是「四捨五入」：−3.3725 → −3.37
		BigDecimal returnPct = pnl.multiply(HUNDRED).divide(totalCost, 2, RoundingMode.HALF_UP);

		return new ProfitResult(buyAmount, buyFee, sellAmount, sellFee, tax, totalCost, pnl, returnPct);
	}

	/**
	 * 手續費：成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元。
	 * 注意是「先捨去、再跟 20 比」：14.25 → 14 → 未滿 20，收 20。
	 */
	public BigDecimal fee(BigDecimal amount) {
		// RoundingMode.DOWN = 無條件捨去（往 0 的方向）；金額都是正數，和 FLOOR 結果相同
		BigDecimal fee = amount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN);
		return fee.max(MIN_FEE);
	}

	/**
	 * 證交稅（只有賣出收）：一般股票 0.3%，ETF 0.1%，無條件捨去到元。
	 *
	 * Part B 第 7 題回報二（債券 ETF 停徵證交稅）若要修正，只需要改這個方法的稅率判斷。
	 */
	public BigDecimal tax(String code, BigDecimal sellAmount) {
		BigDecimal rate = isEtf(code) ? ETF_TAX_RATE : STOCK_TAX_RATE;
		return sellAmount.multiply(rate).setScale(0, RoundingMode.DOWN);
	}

	/**
	 * 是否為 ETF：照題目定義，代號 00 開頭（例：0050、00878、006208）。
	 */
	public boolean isEtf(String code) {
		return code != null && code.startsWith("00");
	}

	private static void requirePositive(BigDecimal value, String name) {
		if (value == null || value.signum() <= 0) {
			throw new IllegalArgumentException(name + "必須大於 0：" + value);
		}
	}
}
