package io.qiao.api;

import java.util.Map;

/**
 * API 欄位錯誤時給使用者看的訊息（App 會直接顯示 message）。
 * 型別錯誤（例如張數送 1.5）和範圍錯誤（例如張數 1000）都用同一句，使用者看到的說明一致。
 */
final class ApiFieldMessages {

	/** 代號沒填或空白 */
	static final String CODE_REQUIRED = "請輸入股票代號";
	/** 代號不是文字（例如送數字 2330 而不是字串 "2330"） */
	static final String CODE_TYPE = "股票代號必須是文字，例如 \"2330\"";
	static final String LOTS = "張數必須是 1～999 的整數";
	static final String BUY_PRICE = "買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字";
	static final String MALFORMED = "請求內容格式不正確，必須是 JSON 物件";

	/** JSON 欄位名稱 → 型別錯誤時的訊息（欄位名稱照規格用小寫加底線） */
	private static final Map<String, String> TYPE_ERROR_BY_FIELD = Map.of(
			"code", CODE_TYPE,
			"lots", LOTS,
			"buy_price", BUY_PRICE);

	private ApiFieldMessages() {
	}

	/** 依欄位名稱取得型別錯誤訊息；不認得的欄位用通用訊息 */
	static String forField(String jsonField) {
		return TYPE_ERROR_BY_FIELD.getOrDefault(jsonField, MALFORMED);
	}
}
