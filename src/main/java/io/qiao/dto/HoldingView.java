package io.qiao.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.qiao.calculation.PortfolioSummary;
import io.qiao.calculation.ProfitCalculator;
import io.qiao.calculation.ProfitResult;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockQuote;

/**
 * 清單上的一筆持股：資料庫的持股資料 + 記憶體快取裡的行情 + 盈虧計算結果。
 * 網頁清單（第 3、4 題）、App API（第 5 題）都從這裡取資料。
 *
 * @param closePrice     目前收盤價（只有 quoteStatus = OK 時有值）
 * @param priceDate      目前收盤價的日期（只有 OK 時有值）
 * @param lastClosePrice 上次成功取得的收盤價（資料庫），取不到行情時顯示「上次收盤價」用
 * @param totalCost      總成本（買進金額 + 買進手續費）；取不到收盤價也一定有值（總計要算進去）
 * @param profit         盈虧計算結果；只有取得收盤價時才有值，否則 null（畫面顯示「無法計算」）
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
		LocalDate lastPriceDate,
		BigDecimal totalCost,
		ProfitResult profit) {

	/**
	 * 由持股 + 快取組成，並計算盈虧。
	 * 快取沒有這檔 → 這次啟動後還沒查過（更新中）。
	 * 只有「查詢成功（OK）」才算盈虧；更新中、未取得、近期無成交資料都當成取不到收盤價。
	 */
	public static HoldingView of(Holding holding, QuoteCache.Entry cacheEntry, ProfitCalculator calculator) {
		QuoteStatus status = cacheEntry == null ? QuoteStatus.UPDATING : cacheEntry.status();
		StockQuote quote = cacheEntry == null ? null : cacheEntry.quote();
		BigDecimal closePrice = quote != null ? quote.closePrice() : null;

		ProfitResult profit = null;
		BigDecimal totalCost;
		if (status == QuoteStatus.OK && closePrice != null) {
			profit = calculator.calculateByLots(holding.getCode(), holding.getLots(), holding.getBuyPrice(), closePrice);
			totalCost = profit.totalCost();
		} else {
			totalCost = calculator.totalCostByLots(holding.getLots(), holding.getBuyPrice());
		}

		return new HoldingView(
				holding.getId(),
				holding.getCode(),
				// 新增或排程補資料時，名稱、市場以官方為準；快取有值就用快取（最新）
				quote != null ? quote.name() : holding.getName(),
				quote != null ? quote.market() : holding.getMarket(),
				holding.getLots(),
				holding.getBuyPrice(),
				status,
				closePrice,
				quote != null ? quote.priceDate() : null,
				holding.getLastClosePrice(),
				holding.getLastPriceDate(),
				totalCost,
				profit);
	}

	/** 有沒有盈虧可以顯示 */
	public boolean hasProfit() {
		return profit != null;
	}

	/** 有沒有「上次收盤價」可以顯示 */
	public boolean hasLastClosePrice() {
		return lastClosePrice != null;
	}

	/**
	 * 無法計算盈虧時，小字說明的原因（使用者決定：區分原因）。
	 * 近期無成交資料 → 讓使用者知道可能停止交易或下市；其他（更新中、未取得）→ 只是暫時還沒拿到。
	 */
	public String missingReason() {
		return quoteStatus == QuoteStatus.NO_RECENT_TRADE ? "近期無成交資料" : "尚未取得收盤價";
	}

	/** 總計用的一筆輸入：有盈虧就用計算結果，沒有就只算總成本 */
	public PortfolioSummary.Line toSummaryLine() {
		return profit != null ? PortfolioSummary.Line.withProfit(profit) : PortfolioSummary.Line.quoteMissing(totalCost);
	}
}
