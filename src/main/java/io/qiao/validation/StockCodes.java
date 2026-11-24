package io.qiao.validation;

import java.util.Locale;

/**
 * 股票代號格式規則（唯一的一份）：網頁表單驗證、前端即時檢查、第 5 題 App API 都用這裡的規則。
 *
 * 規則：4～6 位數字，最後可以有 1 個英文字母。
 * - 一般股票：2330、6488
 * - ETF：0050、00878、006208
 * - 代號含英文字母的合法代號：00679B（債券 ETF）、00632R（反向 ETF）、00631L（槓桿 ETF）、2881A（特別股）
 *   → 不能限制成「只能數字」，否則這些都不能新增（Part B 回報二的使用者就持有 00679B）。
 *
 * 比對前一律先 normalize：去掉所有空白、全形數字與英文轉半形、英文轉大寫
 * （手機中文輸入法容易打出「２３３０」「００６７９ｂ」或多一個空白）。
 */
public final class StockCodes {

	/** 完整格式（Java 的 @Pattern 與前端共用；前端透過頁面上的 data-pattern 取得，不另外寫一份） */
	public static final String PATTERN = "^[0-9]{4,6}[A-Za-z]?$";

	/**
	 * 給 @Pattern 用：多允許「空字串」。
	 * 空白由 @NotBlank 負責顯示「請輸入股票代號」，避免同一個欄位同時出現兩個錯誤訊息。
	 */
	public static final String PATTERN_OR_EMPTY = "^$|" + PATTERN;

	/**
	 * 格式錯誤時的訊息。2.0 網頁的前端檢查（v2-form.js）用同一句（D-49：原本兩邊措辭不同，統一）；改這裡時兩邊要一起改。
	 */
	public static final String MESSAGE = "股票代號格式不正確（4～6 位數字，最後可加 1 個英文字母，例如 2330、0050、00679B）";

	private StockCodes() {
	}

	/**
	 * 統一代號的寫法：
	 * 1. 去掉所有空白（前後、中間；半形空白、全形空白、不換行空白都算）
	 * 2. 全形數字（０～９）、全形英文（Ａ～Ｚ、ａ～ｚ）轉成半形
	 * 3. 英文轉大寫
	 * 例：「　２３ ３０」→「2330」、「００６７９ｂ」→「00679B」。
	 *
	 * 只轉換數字和英文：其他符號（例如①、－）不轉換，照樣判定為格式錯誤，轉換結果才可預期。
	 * （不用 Java 內建的 Unicode 正規化 NFKC：它也會把「①②③④」轉成「1234」，範圍太廣。）
	 *
	 * @return null 時回傳 null
	 */
	public static String normalize(String code) {
		if (code == null) {
			return null;
		}
		StringBuilder result = new StringBuilder(code.length());
		for (char c : code.toCharArray()) {
			if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
				continue; // isSpaceChar 涵蓋全形空白（U+3000）與不換行空白（U+00A0）
			}
			if (c >= '０' && c <= '９') {
				c = (char) (c - '０' + '0');
			} else if (c >= 'Ａ' && c <= 'Ｚ') {
				c = (char) (c - 'Ａ' + 'A');
			} else if (c >= 'ａ' && c <= 'ｚ') {
				c = (char) (c - 'ａ' + 'a');
			}
			result.append(c);
		}
		return result.toString().toUpperCase(Locale.ROOT);
	}

	/** 是否符合格式（先 normalize 再比對；null 或空白都不符合） */
	public static boolean isValid(String code) {
		return code != null && normalize(code).matches(PATTERN);
	}
}
