package io.qiao.exception;

import java.time.YearMonth;

/**
 * 股票代號在上市、上櫃都查不到（兩邊都確實回覆「查無資料」）。
 * 網頁顯示在代號欄位旁；第 5 題的 API 對應 404 STOCK_NOT_FOUND。
 *
 * 注意：只有「兩邊都確定查無」才丟這個例外；只要有一邊連不上，就是 QuoteUnavailableException。
 */
public class StockNotFoundException extends RuntimeException {

	public StockNotFoundException(String code) {
		// 文字保持中性：近兩個月沒有成交資料，可能是代號打錯，也可能是長期停止交易或已下市
		super("查無股票代號 " + code + "：上市、上櫃近兩個月都沒有成交資料（請確認代號，或該股可能已停止交易、下市）");
	}

	private StockNotFoundException(String code, String message) {
		super(message);
	}

	/**
	 * 補登舊交易時（第 6 題 L2 b）：近兩個月與「交易日期那個月」都查不到。
	 *
	 * @param twseNotCovered true = 那個月早於證交所提供的資料範圍（2010 年以前），只查了櫃買中心
	 */
	public static StockNotFoundException inMonth(String code, YearMonth month, boolean twseNotCovered) {
		String when = month.getYear() + " 年 " + month.getMonthValue() + " 月";
		String message = "查無股票代號 " + code + "：上市、上櫃近兩個月與 " + when + "都沒有成交資料"
				+ "（請確認代號與交易日期；只支援上市、上櫃的股票）";
		if (twseNotCovered) {
			message += "。證交所只提供 2010 年起的資料，2010 年以前已下市的上市股票無法確認";
		}
		return new StockNotFoundException(code, message);
	}
}
