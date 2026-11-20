package io.qiao.quote;

import java.time.YearMonth;
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

	/**
	 * 往前多查幾個月，找出「最後一次成交」（不含本月與上個月，那兩個月 fetchLatest 已查過）。
	 * 已存在的持股近兩個月都沒有成交資料時使用：找得到 → 長期停止交易（顯示最後收盤價）；找不到 → 可能已下市。
	 *
	 * @param lookbackMonths 往前再查幾個月（例如 4 → 查兩個月前到五個月前）
	 * @return 找到 → 最後一次成交的行情；都沒有 → Optional.empty()
	 * @throws QuoteUnavailableException 連不上等「無法判斷」的情況
	 */
	Optional<StockQuote> fetchLastTrade(String code, RequestThrottle throttle, int lookbackMonths);

	/**
	 * 查「某一個月」有沒有成交（第 6 題 L2 b：補登已下市股票的交易時，確認交易日期那個月這檔確實有在交易）。
	 *
	 * @return 有成交 → 那個月最後一個交易日的行情（名稱、市場、收盤價）；沒有 → Optional.empty()
	 * @throws QuoteUnavailableException 連不上等「無法判斷」的情況
	 */
	Optional<StockQuote> fetchInMonth(String code, YearMonth month);

	/**
	 * 這個來源最早可以查的月份；更早的月份不送出查詢（無法判斷，不能當成「查無」）。
	 * 證交所的個股日成交資訊只提供 2010-01-04（民國 99 年）起的資料（2026-11-21 實測）。
	 */
	default YearMonth earliestMonth() {
		return YearMonth.of(1990, 1); // 交易日期的下限（TradeService.EARLIEST_DATE）
	}

	/** 不限制請求間隔（新增持股時使用，要在 10 秒內有結果） */
	default Optional<StockQuote> fetchLatest(String code) {
		return fetchLatest(code, RequestThrottle.NONE);
	}
}
