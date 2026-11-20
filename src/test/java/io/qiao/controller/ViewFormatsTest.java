package io.qiao.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 畫面數字格式測試：正負號、千分位、紅賺綠虧。
 */
class ViewFormatsTest {

	private final ViewFormats formats = new ViewFormats();

	@Test
	void 金額加千分位() {
		assertThat(formats.money(new BigDecimal("1301852"))).isEqualTo("1,301,852");
		assertThat(formats.money(new BigDecimal("1301852.00"))).isEqualTo("1,301,852");
	}

	@Test
	void 盈虧帶正負號_0不加符號() {
		assertThat(formats.signedMoney(new BigDecimal("93708"))).isEqualTo("+93,708");
		assertThat(formats.signedMoney(new BigDecimal("-10132"))).isEqualTo("-10,132");
		assertThat(formats.signedMoney(BigDecimal.ZERO)).isEqualTo("0");
	}

	@Test
	void 收益率帶正負號與百分比() {
		assertThat(formats.signedPct(new BigDecimal("9.36"))).isEqualTo("+9.36%");
		assertThat(formats.signedPct(new BigDecimal("-3.37"))).isEqualTo("-3.37%");
		assertThat(formats.signedPct(new BigDecimal("0.00"))).isEqualTo("0.00%");
	}

	@Test
	void 台股慣例_紅色是賺_綠色是虧_0不上色() {
		assertThat(formats.pnlClass(new BigDecimal("1"))).isEqualTo(ViewFormats.PROFIT_CLASS);
		assertThat(formats.pnlClass(new BigDecimal("-1"))).isEqualTo(ViewFormats.LOSS_CLASS);
		assertThat(formats.pnlClass(BigDecimal.ZERO)).isEmpty();
	}
}
