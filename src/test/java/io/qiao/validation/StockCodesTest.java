package io.qiao.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 股票代號格式規則測試：4～6 位數字，最後可以有 1 個英文字母；比對前先去空白、全形轉半形、轉大寫。
 * @ParameterizedTest：同一個測試用多組資料各跑一次，不用每組都寫一個方法。
 */
class StockCodesTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"2330", "6488",              // 一般股票
			"0050", "00878", "006208",   // ETF
			"00679B", "00632R", "00631L", // 代號含英文字母的 ETF
			"2881A",                     // 特別股
			"00679b",                    // 小寫也接受（會轉大寫）
			" 2330 ", "23 30",           // 空白會先去掉（前後、中間都算）
			"２３３０", "００６７９Ｂ", "００６７９ｂ", // 全形數字、全形英文
			"　２３３０　",               // 全形空白
			"２3３0" })                  // 全形半形混合
	void 合法代號(String code) {
		assertThat(StockCodes.isValid(code)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"233",      // 太短
			"1234567",  // 太長
			"台積電",    // 中文
			"A2330",    // 英文在前面
			"2330AB",   // 兩個英文字母
			"2330-1",   // 其他符號
			"①②③④" })  // 圈圈數字不轉換（只轉全形數字與英文，轉換範圍可預期）
	void 不合法代號(String code) {
		assertThat(StockCodes.isValid(code)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "　" })
	void 空白或沒有值_不合法(String code) {
		assertThat(StockCodes.isValid(code)).isFalse();
	}

	@ParameterizedTest
	@CsvSource({
			"'　２３ ３０　', 2330",
			"００６７９ｂ, 00679B",
			"' 00632r ', 00632R",
			"'2881 a', 2881A" })
	void 統一寫法_去空白_全形轉半形_轉大寫(String input, String expected) {
		assertThat(StockCodes.normalize(input)).isEqualTo(expected);
	}

	@Test
	void 統一寫法_null維持null() {
		assertThat(StockCodes.normalize(null)).isNull();
	}
}
