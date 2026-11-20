package io.qiao.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockQuote;

/**
 * 清單上的一筆持股：資料庫的持股資料 + 記憶體快取裡的行情。
 * 網頁清單（第 3 題）、盈虧（第 4 題）、App API（第 5 題）都從這裡取資料。
 *
 * @param closePrice     目前收盤價（只有 quoteStatus = OK 時有值）
 * @param priceDate      目前收盤價的日期（只有 OK 時有值）
 * @param lastClosePrice 上次成功取得的收盤價（資料庫），取不到行情時顯示「上次收盤價」用
 */
public record HoldingView(
		Long id,
		String code,
		String name,
		Market market,
		Integer lots,
		BigDecimal buyPrice,
		QuoteStatus quoteStatus,
		BigDecimal closePrice,
		LocalDate priceDate,
		BigDecimal lastClosePrice,
		LocalDate lastPriceDate) {

	/** 由持股 + 快取組成；快取沒有這檔 → 這次啟動後還沒查過（更新中） */
	public static HoldingView of(Holding holding, QuoteCache.Entry cacheEntry) {
		QuoteStatus status = cacheEntry == null ? QuoteStatus.UPDATING : cacheEntry.status();
		StockQuote quote = cacheEntry == null ? null : cacheEntry.quote();
		return new HoldingView(
				holding.getId(),
				holding.getCode(),
				// 新增或排程補資料時，名稱、市場以官方為準；快取有值就用快取（最新）
				quote != null ? quote.name() : holding.getName(),
				quote != null ? quote.market() : holding.getMarket(),
				holding.getLots(),
				holding.getBuyPrice(),
				status,
				quote != null ? quote.closePrice() : null,
				quote != null ? quote.priceDate() : null,
				holding.getLastClosePrice(),
				holding.getLastPriceDate());
	}

	/** 有沒有目前收盤價（之後算盈虧要用） */
	public boolean hasClosePrice() {
		return quoteStatus == QuoteStatus.OK && closePrice != null;
	}

	/** 有沒有「上次收盤價」可以顯示 */
	public boolean hasLastClosePrice() {
		return lastClosePrice != null;
	}
}
