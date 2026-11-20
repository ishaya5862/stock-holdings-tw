package io.qiao.controller;

import java.math.BigDecimal;
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
