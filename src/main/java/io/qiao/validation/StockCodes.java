package io.qiao.validation;

/**
 * 股票代號格式規則（唯一的一份）：網頁表單驗證、前端即時檢查、第 5 題 App API 都用這裡的規則。
 *
 * 規則：4～6 位數字，最後可以有 1 個英文字母。
 * - 一般股票：2330、6488
 * - ETF：0050、00878、006208
 * - 代號含英文字母的合法代號：00679B（債券 ETF）、00632R（反向 ETF）、00631L（槓桿 ETF）、2881A（特別股）
 *   → 不能限制成「只能數字」，否則這些都不能新增（Part B 回報二的使用者就持有 00679B）。
 * 英文字母大小寫都接受，存檔前由 HoldingService 統一轉成大寫。
 */
public final class StockCodes {

	/** 完整格式（Java 的 @Pattern 與前端共用；前端透過頁面上的 data-pattern 取得，不另外寫一份） */
	public static final String PATTERN = "^[0-9]{4,6}[A-Za-z]?$";

	/**
	 * 給 @Pattern 用：多允許「空字串」。
	 * 空白由 @NotBlank 負責顯示「請輸入股票代號」，避免同一個欄位同時出現兩個錯誤訊息。
	 */
	public static final String PATTERN_OR_EMPTY = "^$|" + PATTERN;

	/** 格式錯誤時的訊息 */
	public static final String MESSAGE = "股票代號為 4～6 位數字，最後可加 1 個英文字母（例：2330、0050、00679B）";

	private StockCodes() {
	}

	/** 是否符合格式（null 或空白都不符合） */
	public static boolean isValid(String code) {
		return code != null && code.trim().matches(PATTERN);
	}
}
