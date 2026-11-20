package io.qiao.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 股票代號格式規則測試：4～6 位數字，最後可以有 1 個英文字母。
 * @ParameterizedTest：同一個測試用多組資料各跑一次，不用每組都寫一個方法。
 */
class StockCodesTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"2330", "6488",           // 一般股票
			"0050", "00878", "006208", // ETF
			"00679B", "00632R", "00631L", // 代號含英文字母的 ETF
			"2881A",                  // 特別股
			"00679b",                 // 小寫也接受（存檔前會轉大寫）
			" 2330 " })               // 前後空白會先去掉
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
			"23 30",    // 中間有空白
			"2330-1" }) // 其他符號
	void 不合法代號(String code) {
		assertThat(StockCodes.isValid(code)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void 空白或沒有值_不合法(String code) {
		assertThat(StockCodes.isValid(code)).isFalse();
	}
}
