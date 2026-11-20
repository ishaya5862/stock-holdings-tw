package io.qiao.exception;

/**
 * 股票代號在上市、上櫃都查不到（兩邊都確實回覆「查無資料」）。
 * 網頁顯示在代號欄位旁；第 5 題的 API 對應 404 STOCK_NOT_FOUND。
 *
 * 注意：只有「兩邊都確定查無」才丟這個例外；只要有一邊連不上，就是 QuoteUnavailableException。
 */
public class StockNotFoundException extends RuntimeException {

	public StockNotFoundException(String code) {
		super("查無股票代號 " + code + "（上市、上櫃都查不到）");
	}
}
