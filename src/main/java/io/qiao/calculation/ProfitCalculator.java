package io.qiao.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 盈虧計算（第 4 題）：假設現在用收盤價全部賣出，淨賺多少。
 *
 * | 項目   | 規則                                                                 |
 * | 手續費 | 成交金額 × 0.1425%，無條件捨去到元，每筆最低 20 元；買進、賣出各收一次 |
 * | 證交稅 | 只有賣出收。一般股票 0.3%，ETF（代號 00 開頭）0.1%；無條件捨去到元     |
 * |        | 2.0：債券 ETF（例 00679B）在停徵期間賣出免稅（第 7 題回報二，依賣出日期）  |
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

	/** 手續費率 0.1425%（public：新增／修改頁的「預估買進成本」由後端把這個值傳給前端，規則只有這一份） */
	public static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
	/** 手續費每筆最低 20 元（public：同上，傳給前端） */
	public static final BigDecimal MIN_FEE = new BigDecimal("20");
	/** 證交稅：一般股票 0.3% */
	private static final BigDecimal STOCK_TAX_RATE = new BigDecimal("0.003");
	/** 證交稅：ETF 0.1% */
	private static final BigDecimal ETF_TAX_RATE = new BigDecimal("0.001");

	/**
	 * 債券 ETF 停徵證交稅的開始日：《證券交易稅條例》第 2 條之 1 第 2 項，106 年 1 月 1 日起（第 7 題回報二）。
	 */
	public static final LocalDate BOND_ETF_EXEMPT_FROM = LocalDate.of(2017, 1, 1);
	/**
	 * 停徵的結束日（預設）：現行條文是 115 年 12 月 31 日。延長到 2036 年的修正案 2026-11-11 初審通過、尚未三讀，
	 * 所以預設照現行法；三讀後改設定 app.tax.bond-etf-exempt-until 即可，不用改程式（Q7-3 b）。
	 */
	public static final LocalDate DEFAULT_BOND_ETF_EXEMPT_UNTIL = LocalDate.of(2026, 12, 31);

	/**
	 * 債券 ETF 的代號：00＋3 碼流水號＋第 6 碼 B（以外幣交易者為 C），例如 00679B。
	 * 依據《中華民國證券市場編碼原則》壹、十三（三）。槓桿型、反向型債券 ETF「準用」L／M、R／S，不會是 B／C，
	 * 剛好就是稅法排除的「槓桿型及反向型」→ 用代號就能判斷，不需要額外的資料來源（Q7-2 a）。
	 */
	private static final Pattern BOND_ETF_CODE = Pattern.compile("^00\\d{3}[BC]$");

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	/** 債券 ETF 停徵證交稅到哪一天（含當天） */
	private final LocalDate bondEtfExemptUntil;

	/** 單元測試直接 new 用：停徵期限照現行法（2026-12-31） */
	public ProfitCalculator() {
		this(DEFAULT_BOND_ETF_EXEMPT_UNTIL.toString());
	}

	/**
	 * Spring 用：停徵期限從設定檔讀（app.tax.bond-etf-exempt-until，可用環境變數 BOND_ETF_TAX_EXEMPT_UNTIL 覆蓋）。
	 * 用字串接再自己轉日期：格式錯誤時啟動就失敗，並清楚說明是哪個設定。
	 */
	@Autowired
	public ProfitCalculator(@Value("${app.tax.bond-etf-exempt-until:2026-12-31}") String bondEtfExemptUntil) {
		try {
			this.bondEtfExemptUntil = LocalDate.parse(bondEtfExemptUntil.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(
					"app.tax.bond-etf-exempt-until 必須是 YYYY-MM-DD 格式的日期：" + bondEtfExemptUntil, e);
		}
	}

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
		// 和 totalCost() 用同一套算法（買進金額 + 買進手續費），兩邊結果一定一致

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
	 * 只算總成本（買進金額 + 買進手續費），不需要收盤價。
	 * 總計時，取不到收盤價的持股仍要算進總成本（規格：quote_missing 的持股算進 total_cost）。
	 */
	public BigDecimal totalCost(long shares, BigDecimal buyPrice) {
		if (shares <= 0) {
			throw new IllegalArgumentException("股數必須大於 0：" + shares);
		}
		requirePositive(buyPrice, "買進價");
		BigDecimal buyAmount = buyPrice.multiply(BigDecimal.valueOf(shares));
		return buyAmount.add(fee(buyAmount));
	}

	/** 只算總成本，用張數（1.0 版使用） */
	public BigDecimal totalCostByLots(int lots, BigDecimal buyPrice) {
		return totalCost((long) lots * SHARES_PER_LOT, buyPrice);
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
	 * 證交稅（不看日期）：一般股票 0.3%，ETF 0.1%，無條件捨去到元。第 4 題的規則（calculate 使用）。
	 * 實際的持股計算（v1、v2、網頁）都經過 PositionCalculator，呼叫下面「帶賣出日期」的版本。
	 */
	public BigDecimal tax(String code, BigDecimal sellAmount) {
		BigDecimal rate = isEtf(code) ? ETF_TAX_RATE : STOCK_TAX_RATE;
		return sellAmount.multiply(rate).setScale(0, RoundingMode.DOWN);
	}

	/**
	 * 證交稅，依「賣出日期」判斷（2.0 的 PositionCalculator 使用；C1-3 預留的日期參數）。
	 *
	 * 第 7 題回報二：債券 ETF 在停徵期間（2017-01-01～停徵期限，含頭尾）賣出 → 0 元；
	 * 期間以外照一般 ETF 0.1%。已實現損益用當時的賣出日期；未實現損益用估價日（台北時間的今天），
	 * 所以停徵到期、沒有延長時，從隔天起會自動恢復扣稅，不用改程式。
	 *
	 * @param tradeDate 賣出日期（未實現損益是估價日）
	 */
	public BigDecimal tax(String code, BigDecimal sellAmount, LocalDate tradeDate) {
		if (isBondEtfTaxExempt(code, tradeDate)) {
			return BigDecimal.ZERO;
		}
		return tax(code, sellAmount);
	}

	/** 這一天賣出這檔，證交稅是否停徵（債券 ETF＋停徵期間內） */
	public boolean isBondEtfTaxExempt(String code, LocalDate sellDate) {
		return isBondEtf(code) && sellDate != null
				&& !sellDate.isBefore(BOND_ETF_EXEMPT_FROM) && !sellDate.isAfter(bondEtfExemptUntil);
	}

	/** 債券 ETF 停徵證交稅到哪一天（網頁試算用，規則只有後端這一份） */
	public LocalDate bondEtfExemptUntil() {
		return bondEtfExemptUntil;
	}

	/**
	 * 是否為 ETF：照題目定義，代號 00 開頭（例：0050、00878、006208）。
	 */
	public boolean isEtf(String code) {
		return code != null && code.startsWith("00");
	}

	/** 是否為債券 ETF（不含槓桿型、反向型）：代號第 6 碼是 B 或 C，例如 00679B */
	public boolean isBondEtf(String code) {
		return code != null && BOND_ETF_CODE.matcher(code).matches();
	}

	private static void requirePositive(BigDecimal value, String name) {
		if (value == null || value.signum() <= 0) {
			throw new IllegalArgumentException(name + "必須大於 0：" + value);
		}
	}
}
