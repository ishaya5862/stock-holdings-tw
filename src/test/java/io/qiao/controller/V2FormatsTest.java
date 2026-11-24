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

	// ===== 表格的大數字簡寫（優化 v2-number-display，D-40、D-45）=====
	// 規則：絕對值 ≥ 100 萬才簡寫（D-45 從 1,000 萬改成 100 萬）；萬取整數（四捨五入、千分位）；
	// 四捨五入後滿 10,000 萬改用「億」（兩位小數）。簡寫時提示放完整數字＋單位；沒有簡寫時是 null（不加提示）。

	@Test
	void 簡寫_未滿100萬完整顯示_沒有提示() {
		assertThat(formats.shortMoney("999999.99")).isEqualTo("999,999.99");
		assertThat(formats.isAbbreviated("999999.99")).isFalse();
		assertThat(formats.moneyTitle("999999.99")).isNull();
		assertThat(formats.shortMoney("368022.75")).isEqualTo("368,022.75");
	}

	@Test
	void 簡寫_滿100萬用萬_四捨五入到萬_提示是完整金額() {
		assertThat(formats.shortMoney("1000000.00")).isEqualTo("100 萬");
		assertThat(formats.shortMoney("1234567.00")).isEqualTo("123 萬");     // 123.4567 萬 → 123
		assertThat(formats.shortMoney("1235000.00")).isEqualTo("124 萬");     // 123.5 萬 → 124（四捨五入）
		assertThat(formats.shortMoney("9999999.99")).isEqualTo("1,000 萬");   // 999.9999… 萬 → 1,000
		assertThat(formats.shortMoney("12345678.00")).isEqualTo("1,235 萬");  // 1,234.5678 萬 → 1,235
		assertThat(formats.shortMoney("12344999.99")).isEqualTo("1,234 萬");  // 1,234.4999… 萬 → 1,234
		assertThat(formats.isAbbreviated("1000000.00")).isTrue();
		assertThat(formats.moneyTitle("1234567.00")).isEqualTo("1,234,567.00 元");
	}

	@Test
	void 簡寫_滿1億用億_兩位小數_四捨五入後滿1億也改用億() {
		assertThat(formats.shortMoney("123456789.00")).isEqualTo("1.23 億");
		assertThat(formats.shortMoney("12345678900.00")).isEqualTo("123.46 億");
		assertThat(formats.shortMoney("99994999.00")).isEqualTo("9,999 萬");  // 9,999.4999 萬 → 9,999
		assertThat(formats.shortMoney("99995000.00")).isEqualTo("1.00 億");   // 9,999.5 萬會進位成 10,000 萬 → 改用億
	}

	@Test
	void 簡寫_帶正負號_負號用數學減號_提示也帶正負號() {
		assertThat(formats.shortSignedMoney("12345678.00")).isEqualTo("+1,235 萬");
		assertThat(formats.shortSignedMoney("-12345678.00")).isEqualTo("−1,235 萬");
		assertThat(formats.shortSignedMoney("-20252.25")).isEqualTo("−20,252.25");
		assertThat(formats.isAbbreviated("-1000000.00")).isTrue();
		assertThat(formats.isAbbreviated("-999999.99")).isFalse();
		assertThat(formats.signedMoneyTitle("-12345678.00")).isEqualTo("−12,345,678.00 元");
		assertThat(formats.signedMoneyTitle("-20252.25")).isNull();
	}

	@Test
	void 簡寫_沒有值顯示破折號_沒有提示() {
		assertThat(formats.shortMoney(null)).isEqualTo("—");
		assertThat(formats.shortSignedMoney(null)).isEqualTo("—");
		assertThat(formats.isAbbreviated(null)).isFalse();
		assertThat(formats.moneyTitle(null)).isNull();
	}

	@Test
	void 簡寫_股數用萬股億股_提示是完整股數() {
		assertThat(formats.shortShares(999_999L)).isEqualTo("999,999");
		assertThat(formats.sharesTitle(999_999L)).isNull();
		assertThat(formats.shortShares(1_000_000L)).isEqualTo("100 萬股");
		assertThat(formats.shortShares(12_345_678L)).isEqualTo("1,235 萬股");
		assertThat(formats.sharesTitle(12_345_678L)).isEqualTo("12,345,678 股");
		assertThat(formats.shortShares(2_199_999_978L)).isEqualTo("22.00 億股");
		assertThat(formats.shortShares(null)).isEqualTo("—");
		assertThat(formats.sharesTitle(null)).isNull();
	}

	// 摘要卡的提示（D-43）：卡片顯示完整金額，滑鼠移上去或點一下顯示「約 X 萬元」；門檻和表格一樣（D-45 起 100 萬），未滿沒有提示

	@Test
	void 摘要卡提示_滿100萬顯示約幾萬元_未滿沒有提示() {
		assertThat(formats.approxMoney("12017100.00")).isEqualTo("約 1,202 萬元");   // 1,201.71 萬 → 1,202
		assertThat(formats.approxMoney("1001796261.67")).isEqualTo("約 10.02 億元");
		assertThat(formats.approxMoney("2854000.00")).isEqualTo("約 285 萬元");      // 285.4 萬 → 285
		assertThat(formats.approxMoney("999999.99")).isNull();
		assertThat(formats.approxMoney(null)).isNull();
	}

	@Test
	void 摘要卡提示_損益帶正負號() {
		assertThat(formats.approxSignedMoney("12345678.00")).isEqualTo("約 +1,235 萬元");
		assertThat(formats.approxSignedMoney("-12345678.00")).isEqualTo("約 −1,235 萬元");
		assertThat(formats.approxSignedMoney("925375.00")).isNull();                 // 未滿 100 萬
		assertThat(formats.approxSignedMoney(null)).isNull();
	}

	@Test
	void 取不到收盤價的原因_用詞和第一版網頁相同() {
		// D-50：持有中卻沒有市值時，那一列顯示「無法計算」＋原因（原本只有「—」）
		assertThat(formats.quoteMissingReason("NO_RECENT_TRADE")).isEqualTo("近期無成交資料");
		assertThat(formats.quoteMissingReason("UNAVAILABLE")).isEqualTo("尚未取得收盤價");
		assertThat(formats.quoteMissingReason("UPDATING")).isEqualTo("尚未取得收盤價");
	}

	@Test
	void 市場別標籤的顏色_上市上櫃不同_不知道就不加() {
		// D-54：上市、上櫃用不同顏色的標籤（刻意不用紅綠，避免和賺賠混淆）
		assertThat(formats.marketClass("TWSE")).isEqualTo("v2-market v2-market-twse");
		assertThat(formats.marketClass("TPEX")).isEqualTo("v2-market v2-market-tpex");
		assertThat(formats.marketClass(null)).isEmpty();
	}

	@Test
	void 收益率標籤_漲跌方向_紅漲綠跌_0與沒有值是持平() {
		// D-54：摘要卡的百分比做成有底色的標籤＋箭頭
		assertThat(formats.pctTrend("13.62")).isEqualTo("up");
		assertThat(formats.pctTrend("-82.88")).isEqualTo("down");
		assertThat(formats.pctTrend("0.00")).isEqualTo("flat");
		assertThat(formats.pctTrend(null)).isEqualTo("flat");
	}

	@Test
	void 市場別() {
		assertThat(formats.market("TWSE")).isEqualTo("上市");
		assertThat(formats.market("TPEX")).isEqualTo("上櫃");
		assertThat(formats.market(null)).isEqualTo("—");
	}
}
