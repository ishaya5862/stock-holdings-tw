package io.qiao.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import org.springframework.stereotype.Component;

/**
 * 畫面上數字的格式（給 Thymeleaf 呼叫：${@viewFormats.signedMoney(x)}）。
 *
 * 為什麼另外寫一個類別：帶正負號、千分位、紅綠色的判斷如果全寫在模板裡會很長又重複；
 * 集中在這裡，清單每一筆和頁面上方的總計都用同一套規則，也能寫單元測試。
 */
@Component
public class ViewFormats {

	/** 台股慣例：紅色是賺 */
	static final String PROFIT_CLASS = "text-profit";
	/** 台股慣例：綠色是虧 */
	static final String LOSS_CLASS = "text-loss";

	/** 金額（整數元，千分位），例如 1,301,852 */
	public String money(BigDecimal value) {
		return moneyFormat().format(value.setScale(0, RoundingMode.DOWN));
	}

	/** 帶正負號的金額，例如 +93,708、-10,132；0 不加符號 */
	public String signedMoney(BigDecimal value) {
		return sign(value) + money(value.abs());
	}

	/** 帶正負號的百分比，例如 +9.36%、-3.37%；0 顯示 0.00% */
	public String signedPct(BigDecimal value) {
		return sign(value) + pctFormat().format(value.abs()) + "%";
	}

	/** 盈虧的顏色：賺 → 紅、虧 → 綠、0 → 不上色（黑） */
	public String pnlClass(BigDecimal value) {
		return switch (value.signum()) {
			case 1 -> PROFIT_CLASS;
			case -1 -> LOSS_CLASS;
			default -> "";
		};
	}

	private static String sign(BigDecimal value) {
		return value.signum() > 0 ? "+" : value.signum() < 0 ? "-" : "";
	}

	// DecimalFormat 不是執行緒安全的（多人同時開頁面會互相干擾），所以每次建立新的，不共用
	private static DecimalFormat moneyFormat() {
		return new DecimalFormat("#,##0");
	}

	private static DecimalFormat pctFormat() {
		return new DecimalFormat("#,##0.00");
	}
}
