package io.qiao.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.qiao.entity.Market;

/**
 * 查詢官方行情的結果：某檔股票「官方已公布的最近一個交易日」收盤價。
 *
 * @param code       股票代號
 * @param name       股票名稱
 * @param market     上市（TWSE）或上櫃（TPEX）
 * @param closePrice 收盤價
 * @param priceDate  收盤價的日期（資料本身的日期，不一定是今天：週末、假日、13:30 前都會是更早的交易日）
 */
public record StockQuote(
		String code,
		String name,
		Market market,
		BigDecimal closePrice,
		LocalDate priceDate) {
}
