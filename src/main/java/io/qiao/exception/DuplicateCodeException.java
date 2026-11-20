package io.qiao.exception;

/**
 * 股票代號重複（同一個代號只能有一筆）。
 * 網頁顯示在代號欄位旁；第 5 題的 API 對應 409 DUPLICATE_CODE。
 */
public class DuplicateCodeException extends RuntimeException {

	private final String code;

	public DuplicateCodeException(String code) {
		// 訊息格式照 App 1.0 API 規格的範例：「2330 已在持股中」
		super(code + " 已在持股中");
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
