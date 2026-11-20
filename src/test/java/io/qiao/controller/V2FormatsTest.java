package io.qiao.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 2.0 新網頁的數字格式（第 6 題 C8）。輸入是 App 2.0 API 的金額字串（兩位小數）。
 */
class V2FormatsTest {

	private final V2Formats formats = new V2Formats();

	@Test
	void 金額_千分位兩位小數_沒有值顯示破折號() {
		assertThat(formats.money("368022.75")).isEqualTo("368,022.75");
		assertThat(formats.money("0.00")).isEqualTo("0.00");
		assertThat(formats.money(null)).isEqualTo("—");
	}

	@Test
	void 最大金額也不失真() {
		// 99,999,999 股 × 99,999,999.99 元：超過 double 的精確範圍，用 BigDecimal 才不會變成 …000.00
		assertThat(formats.money("9999999899000000.01")).isEqualTo("9,999,999,899,000,000.01");
	}

	@Test
	void 帶正負號的金額與百分比_負號用數學減號_0不加符號() {
		assertThat(formats.signedMoney("20252.25")).isEqualTo("+20,252.25");
		assertThat(formats.signedMoney("-7157.00")).isEqualTo("−7,157.00");
		assertThat(formats.signedMoney("0.00")).isEqualTo("0.00");
		assertThat(formats.signedMoney(null)).isEqualTo("—");
		assertThat(formats.signedPct("5.50")).isEqualTo("+5.50%");
		assertThat(formats.signedPct("-5.67")).isEqualTo("−5.67%");
		assertThat(formats.signedPct(null)).isEqualTo("—");
	}

	@Test
	void 盈虧顏色_紅賺綠虧_0與沒有值不上色() {
		assertThat(formats.pnlClass("0.01")).isEqualTo("text-profit");
		assertThat(formats.pnlClass("-0.01")).isEqualTo("text-loss");
		assertThat(formats.pnlClass("0.00")).isEmpty();
		assertThat(formats.pnlClass(null)).isEmpty();
	}

	@Test
	void 股數與張數換算() {
		assertThat(formats.shares(1500L)).isEqualTo("1,500");
		assertThat(formats.shares(null)).isEqualTo("—");
		assertThat(formats.lots(1500L)).isEqualTo("1 張又 500 股");
		assertThat(formats.lots(2000L)).isEqualTo("2 張");
		assertThat(formats.lots(300L)).isEqualTo("300 股（零股）");
		assertThat(formats.lots(0L)).isEmpty();
		assertThat(formats.lots(null)).isEmpty();
	}

	@Test
	void 市場別() {
		assertThat(formats.market("TWSE")).isEqualTo("上市");
		assertThat(formats.market("TPEX")).isEqualTo("上櫃");
		assertThat(formats.market(null)).isEqualTo("—");
	}
}
