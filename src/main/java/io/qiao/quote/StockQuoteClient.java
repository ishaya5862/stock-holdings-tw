package io.qiao.quote;

import java.util.Optional;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;

/**
 * 向某一個官方來源（證交所或櫃買中心）查詢股票行情。
 *
 * 定義成介面的原因：單元測試可以換成假的實作，測試時不會真的連到官方網站（題目第 4 題的要求）。
 */
public interface StockQuoteClient {

	/** 這個來源是哪個市場 */
	Market market();

	/**
	 * 查詢「官方已公布的最近一個交易日」的收盤價。
	 *
	 * @param throttle 控制每次請求的間隔（背景排程用；新增持股時用 RequestThrottle.NONE）
	 * @return 查到 → 行情；這個市場沒有這檔股票 → Optional.empty()
	 * @throws QuoteUnavailableException 連不上、逾時、回應格式不對等「無法判斷」的情況
	 *         （和「查無此股票」要分開：連不上時不能說代號不存在）
	 */
	Optional<StockQuote> fetchLatest(String code, RequestThrottle throttle);

	/** 不限制請求間隔（新增持股時使用，要在 10 秒內有結果） */
	default Optional<StockQuote> fetchLatest(String code) {
		return fetchLatest(code, RequestThrottle.NONE);
	}
}
