package io.qiao.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import org.springframework.stereotype.Component;

/**
 * 2.0 新網頁的數字格式（Thymeleaf 呼叫：${@v2Formats.money(h.totalCost)}）。第 6 題 C8。
 *
 * 為什麼收「字串」：新網頁直接用 App 2.0 API 的回應物件（HoldingV2Response 等，C8-1 a），
 * 金額是兩位小數的字串（"20252.25"），網頁和 App 看到的數字一定相同；這裡只負責加千分位、正負號、顏色。
 * 解析用 BigDecimal（不用 double），最大金額約 10^16 元也不會失真。
 *
 * 和第一版 ViewFormats 分開：第一版金額顯示到「元」，2.0 顯示到「分」，規則不同，混在一起容易用錯。
 */
@Component
public class V2Formats {

	/** 沒有值（0 股的平均成本、取不到收盤價的市值等）顯示的符號 */
	static final String NONE = "—";
	/** 負號用數學減號「−」（比連字號「-」寬，和「+」對齊；2.0 預覽版使用者確認的樣式） */
	static final String MINUS = "−";

	/** 金額（兩位小數、千分位），例如 "20252.25" → 20,252.25；null → — */
	public String money(String amount) {
		return amount == null ? NONE : moneyFormat().format(new BigDecimal(amount));
	}

	/** 帶正負號的金額，例如 +20,252.25、−7,157.00；0 不加符號；null → — */
	public String signedMoney(String amount) {
		if (amount == null) {
			return NONE;
		}
		BigDecimal value = new BigDecimal(amount);
		return sign(value) + moneyFormat().format(value.abs());
	}

	/** 帶正負號的百分比，例如 +5.50%；null → — */
	public String signedPct(String pct) {
		return pct == null ? NONE : signedMoney(pct) + "%";
	}

	/** 盈虧的顏色（台股慣例：紅賺、綠虧、0 不上色）；null → 不上色 */
	public String pnlClass(String amount) {
		if (amount == null) {
			return "";
		}
		return switch (new BigDecimal(amount).signum()) {
			case 1 -> ViewFormats.PROFIT_CLASS;
			case -1 -> ViewFormats.LOSS_CLASS;
			default -> "";
		};
	}

	/** 股數（千分位），例如 1500 → 1,500；null → — */
	public String shares(Long shares) {
		return shares == null ? NONE : new DecimalFormat("#,##0").format(shares);
	}

	/** 股數換算成張（1 張 = 1,000 股），例如 1500 → 1 張又 500 股、300 → 300 股（零股）、2000 → 2 張 */
	public String lots(Long shares) {
		if (shares == null || shares == 0) {
			return "";
		}
		long lots = shares / 1000;
		long odd = shares % 1000;
		if (lots == 0) {
			return odd + " 股（零股）";
		}
		return odd == 0 ? lots + " 張" : lots + " 張又 " + odd + " 股";
	}

	// ===== 表格的大數字簡寫（優化 v2-number-display，D-40、D-45）=====
	// 為什麼：表格內容不換行，12,345,678.00 這種長數字會把欄位撐很寬，後面的欄位就看不到。
	// 為什麼用「萬、億」：台股使用者習慣的讀法，比 12M 好懂。
	// 只用在表格；摘要卡顯示完整金額（使用者要求），API 一律是完整數字。
	// 簡寫一定會四捨五入，所以畫面上要搭配提示框（滑鼠移上去或點一下看完整數字，D-43）與虛線。

	/**
	 * 簡寫門檻：絕對值 ≥ 100 萬才簡寫（比這小最多 999,999.99，10 個字）。
	 * D-45：原本是 1,000 萬，使用者改成 100 萬——表格更窄，手續費、證交稅這類較小的金額也會簡寫。
	 */
	private static final BigDecimal ABBREVIATE_FROM = new BigDecimal("1000000");
	private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
	private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");

	/** 這個金額在表格裡會不會被簡寫（決定要不要加虛線與 title） */
	public boolean isAbbreviated(String amount) {
		return amount != null && isLarge(new BigDecimal(amount));
	}

	/** 表格用的金額：≥ 100 萬簡寫成「123 萬」「1,235 萬」「1.23 億」，否則同 money()；null → — */
	public String shortMoney(String amount) {
		if (amount == null) {
			return NONE;
		}
		BigDecimal value = new BigDecimal(amount);
		return isLarge(value) ? abbreviate(value, "") : money(amount);
	}

	/** 表格用的帶正負號金額：+1,235 萬、−1,235 萬；未滿 100 萬同 signedMoney() */
	public String shortSignedMoney(String amount) {
		if (amount == null) {
			return NONE;
		}
		BigDecimal value = new BigDecimal(amount);
		return isLarge(value) ? sign(value) + abbreviate(value.abs(), "") : signedMoney(amount);
	}

	/** 簡寫金額的提示（滑鼠移上去看到）：完整金額＋「元」；沒有簡寫時 null（Thymeleaf 會省略 title 屬性） */
	public String moneyTitle(String amount) {
		return isAbbreviated(amount) ? money(amount) + " 元" : null;
	}

	/** 同 moneyTitle()，但帶正負號（給損益欄） */
	public String signedMoneyTitle(String amount) {
		return isAbbreviated(amount) ? signedMoney(amount) + " 元" : null;
	}

	/**
	 * 摘要卡的提示（D-43）：卡片本身顯示完整金額，滑鼠移上去或點一下顯示「約 1,202 萬元」，大數字不用一位一位數。
	 * 門檻和表格一樣（≥ 100 萬，使用者決定）；未滿或沒有值時 null（不顯示提示）。「約」表示有四捨五入。
	 */
	public String approxMoney(String amount) {
		return isAbbreviated(amount) ? "約 " + shortMoney(amount) + "元" : null;
	}

	/** 同 approxMoney()，但帶正負號（給損益卡）：約 +1,235 萬元、約 −1,235 萬元 */
	public String approxSignedMoney(String amount) {
		return isAbbreviated(amount) ? "約 " + shortSignedMoney(amount) + "元" : null;
	}

	/** 表格用的股數：≥ 100 萬股簡寫成「123 萬股」「1,235 萬股」，否則同 shares()；null → — */
	public String shortShares(Long shares) {
		if (shares == null) {
			return NONE;
		}
		BigDecimal value = BigDecimal.valueOf(shares);
		return isLarge(value) ? abbreviate(value, "股") : shares(shares);
	}

	/** 簡寫股數的提示：完整股數＋「股」；沒有簡寫時 null */
	public String sharesTitle(Long shares) {
		return shares != null && isLarge(BigDecimal.valueOf(shares)) ? shares(shares) + " 股" : null;
	}

	private static boolean isLarge(BigDecimal value) {
		return value.abs().compareTo(ABBREVIATE_FROM) >= 0;
	}

	/**
	 * 簡寫（不含正負號，呼叫端自己加）：先換算成「萬」四捨五入到整數；
	 * 進位後滿 10,000 萬（例如 99,995,000 → 10,000 萬）就改用「億」，留兩位小數，避免出現「10,000 萬」。
	 *
	 * @param unit 接在萬、億後面的單位（金額不加；股數加「股」）
	 */
	private static String abbreviate(BigDecimal value, String unit) {
		BigDecimal wan = value.divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP);
		if (wan.abs().compareTo(TEN_THOUSAND) < 0) {
			return new DecimalFormat("#,##0").format(wan) + " 萬" + unit;
		}
		BigDecimal yi = value.divide(HUNDRED_MILLION, 2, RoundingMode.HALF_UP);
		return moneyFormat().format(yi) + " 億" + unit;
	}

	/**
	 * 持有中卻算不出市值、損益時的原因（D-50）：用詞和第一版網頁（HoldingView.missingReason）相同。
	 * 近期無成交 → 可能停止交易或下市；其他（更新中、官方連不上）→ 只是暫時還沒拿到。
	 */
	public String quoteMissingReason(String quoteStatus) {
		return "NO_RECENT_TRADE".equals(quoteStatus) ? "近期無成交資料" : "尚未取得收盤價";
	}

	/** 市場別：TWSE → 上市、TPEX → 上櫃；還不知道 → — */
	public String market(String market) {
		if (market == null) {
			return NONE;
		}
		return switch (market) {
			case "TWSE" -> "上市";
			case "TPEX" -> "上櫃";
			default -> market;
		};
	}

	private static String sign(BigDecimal value) {
		return value.signum() > 0 ? "+" : value.signum() < 0 ? MINUS : "";
	}

	// DecimalFormat 不是執行緒安全的（多人同時開頁面會互相干擾），每次建立新的
	private static DecimalFormat moneyFormat() {
		return new DecimalFormat("#,##0.00");
	}
}
