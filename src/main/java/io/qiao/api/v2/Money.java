package io.qiao.api.v2;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v2 的金額、價格、收益率一律輸出成「兩位小數的字串」（第 6 題 C5-2 b），例如 "20252.25"、"256.00"。
 *
 * 為什麼用字串：2.0 的金額會有「分」，最大約 10^16 元；JSON 的數字在很多語言（包括 JavaScript）會被當成浮點數，
 * 超過約 9×10^15 就會失真，也有 0.1 + 0.2 ≠ 0.3 的誤差。字串可以讓 App 用精確小數型別解析（Java BigDecimal 等）。
 * 業界的支付、券商 API 也常用字串表示金額。股數是整數，最大也不會超過 JavaScript 的精確範圍，維持 JSON 數字。
 */
final class Money {

	private Money() {
	}

	/**
	 * 兩位小數的字串；null 照樣輸出 null（欄位一定出現，值是 null）。
	 * 金額本身最多兩位小數（價格兩位、手續費與稅是整數元、分攤成本到分），這裡不會捨入任何值；
	 * 萬一出現更多位數，UNNECESSARY 會直接報錯，不會默默改掉數字。
	 */
	static String of(BigDecimal amount) {
		return amount == null ? null : amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
	}
}
