package io.qiao.api;

import java.util.Map;

/**
 * API 欄位錯誤時給使用者看的訊息（App 會直接顯示 message）。
 * 型別錯誤（例如張數送 1.5）和範圍錯誤（例如張數 1000）都用同一句，使用者看到的說明一致。
 */
final class ApiFieldMessages {

	static final String CODE = "請輸入股票代號";
	static final String LOTS = "張數必須是 1～999 的整數";
	static final String BUY_PRICE = "買進價必須是大於 0、最多兩位小數的數字";
	static final String MALFORMED = "請求內容格式不正確，必須是 JSON 物件";

	/** JSON 欄位名稱 → 訊息（欄位名稱照規格用小寫加底線） */
	private static final Map<String, String> BY_FIELD = Map.of(
			"code", CODE,
			"lots", LOTS,
			"buy_price", BUY_PRICE);

	private ApiFieldMessages() {
	}

	/** 依欄位名稱取得訊息；不認得的欄位用通用訊息 */
	static String forField(String jsonField) {
		return BY_FIELD.getOrDefault(jsonField, MALFORMED);
	}
}
