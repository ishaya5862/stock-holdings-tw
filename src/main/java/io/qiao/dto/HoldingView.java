package io.qiao.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import io.qiao.calculation.OversellException;
import io.qiao.calculation.PortfolioSummary;
import io.qiao.calculation.PositionCalculator;
import io.qiao.calculation.PositionResult;
import io.qiao.calculation.ProfitResult;
import io.qiao.calculation.TradeSide;
import io.qiao.calculation.Unrealized;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.entity.Trade;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockQuote;

/**
 * 1.0 介面（App 1.0 的 /api/v1、第一版網頁）看到的一筆持股：持股＋股票資訊＋行情＋由交易紀錄重算的結果。
 *
 * 2.0（第 6 題 C4）起張數、買進價不再存在 holdings，改由交易紀錄重算後「換成 1.0 的樣子」：
 * | lots      | 股數 ÷ 1,000，無條件捨去（C4-1：絕不多報；零股另外加註）                                  |
 * | buyPrice  | 平均買進價（不含手續費，C4-2）：只有一筆買進時剛好等於原本的買進價                           |
 * | totalCost | 剩餘成本（含買進手續費）四捨五入到元                                                        |
 * | profit    | 未實現損益；市值、盈虧四捨五入到元（C4-6：每筆先捨入，總計用捨入後的數字加總，清單加起來 = 總計）|
 * 1.0 的資料（整張、一筆買進）金額本來就是整數，捨入不會改變任何數字，App 1.0 使用者看到的和升級前完全相同。
 *
 * @param shares         真實股數（盈虧、市值都用它算，不用捨去後的張數）
 * @param lots           1.0 的張數（捨去）
 * @param buyPrice       1.0 的買進價（平均買進價，不含手續費）
 * @param closePrice     目前收盤價（只有 quoteStatus = OK 時有值）
 * @param priceDate      目前收盤價的日期（只有 OK 時有值）
 * @param lastClosePrice 上次成功取得的收盤價（資料庫 stocks），取不到行情時顯示「上次收盤價」用
 * @param totalCost      剩餘成本（含買進手續費，到元）；取不到收盤價也一定有值（總計要算進去）
 * @param profit         盈虧計算結果；只有取得收盤價時才有值，否則 null（畫面顯示「無法計算」）
 * @param v1Limits       1.0 表達不了的原因（零股、多次買進、賣出）；空的 = 1.0 可以完整表達、可以修改
 * @param hasCents       精確金額有「分」（第一版網頁要附註「四捨五入到元，可能和 2.0 相差幾元」）
 * @param dataError      資料異常的原因（交易時間線不合理，算不出來）；正常時為 null。異常時 lots、buyPrice、totalCost 為 null
 */
public record HoldingView(
		Long id,
		String code,
		String name,
		Market market,
		long shares,
		Long lots,
		BigDecimal buyPrice,
		QuoteStatus quoteStatus,
		BigDecimal closePrice,
		LocalDate priceDate,
		BigDecimal lastClosePrice,
		LocalDate lastPriceDate,
		BigDecimal totalCost,
		ProfitResult profit,
		List<String> v1Limits,
		boolean hasCents,
		String dataError) {

	private static final long SHARES_PER_LOT = 1000;

	/**
	 * 由持股＋股票資訊＋快取＋這筆持股的全部交易組成，並重算。
	 * 快取沒有這檔 → 這次啟動後還沒查過（更新中）。
	 * 只有「查詢成功（OK）」才算盈虧；更新中、未取得、近期無成交資料都當成取不到收盤價。
	 *
	 * @param stock  股票資訊（stocks）；外鍵保證一定有，但仍容許 null（防禦性寫法）
	 * @param trades 這筆持股的全部交易
	 * @param today  台北時區的今天（未實現損益的證交稅用；海外主機不受影響）
	 */
	public static HoldingView of(Holding holding, Stock stock, QuoteCache.Entry cacheEntry, List<Trade> trades,
			PositionCalculator calculator, LocalDate today) {
		QuoteStatus status = cacheEntry == null ? QuoteStatus.UPDATING : cacheEntry.status();
		StockQuote quote = cacheEntry == null ? null : cacheEntry.quote();
		// 顯示用的收盤價、日期：和 1.0 相同，快取有行情就有值；只有「算盈虧」限定查詢成功（OK）
		BigDecimal closePrice = quote != null ? quote.closePrice() : null;
		LocalDate priceDate = quote != null ? quote.priceDate() : null;
		BigDecimal priceForProfit = status == QuoteStatus.OK ? closePrice : null;

		String name = quote != null ? quote.name() : (stock != null ? stock.getName() : null);
		Market market = quote != null ? quote.market() : (stock != null ? stock.getMarket() : null);

		PositionResult position;
		try {
			position = calculator.calculate(holding.getCode(), trades.stream().map(Trade::toInput).toList(),
					priceForProfit, today);
		} catch (OversellException e) {
			// 故障隔離（朋友回饋 1-1 a）：交易時間線不合理（正常操作不會發生），只把這一檔標成「資料異常」，
			// 其他持股與總計照常。v1 不輸出這一檔（1-2 a），第一版網頁顯示原因；log 由呼叫端（HoldingService）記錄
			return new HoldingView(holding.getId(), holding.getCode(), name, market, 0, null, null, status, closePrice,
					priceDate, stock != null ? stock.getLastClosePrice() : null,
					stock != null ? stock.getLastPriceDate() : null, null, null, List.of(), false, e.getMessage());
		}

		BigDecimal totalCost = toYuan(position.remainingCost());
		ProfitResult profit = null;
		boolean hasCents = hasCents(position.remainingCost());
		Unrealized unrealized = position.unrealized();
		if (unrealized != null) {
			// 沿用第 4 題的 ProfitResult（總計、畫面、API 都認得它）；成本換成「剩餘成本」，金額捨入到元
			profit = new ProfitResult(
					position.remainingAmount(),
					position.remainingCost().subtract(position.remainingAmount()),
					toYuan(unrealized.marketValue()),
					unrealized.sellFee(),
					unrealized.tax(),
					totalCost,
					toYuan(unrealized.pnl()),
					unrealized.returnPct());
			hasCents = hasCents || hasCents(unrealized.marketValue()) || hasCents(unrealized.pnl());
		}

		return new HoldingView(
				holding.getId(),
				holding.getCode(),
				name, // 名稱、市場以官方為準：快取有值就用快取（這次啟動後查到的最新資料），否則用股票表
				market,
				position.shares(),
				position.shares() / SHARES_PER_LOT,
				position.averageBuyPrice(),
				status,
				closePrice,
				priceDate,
				stock != null ? stock.getLastClosePrice() : null,
				stock != null ? stock.getLastPriceDate() : null,
				totalCost,
				profit,
				v1Limits(position.shares(), trades),
				hasCents,
				null);
	}

	/** 資料異常（交易時間線不合理、算不出來）：v1 不輸出、總計不算、網頁顯示原因 */
	public boolean hasDataError() {
		return dataError != null;
	}

	/**
	 * 1.0 表達不了的原因（C4-7）。空的 = 和 1.0 的資料一樣（整張、一筆買進），1.0 可以完整顯示、修改。
	 * App 1.0 的 name 加註、修改（C4-3）的判斷都用它，規則只有一份。
	 */
	static List<String> v1Limits(long shares, List<Trade> trades) {
		List<String> reasons = new ArrayList<>();
		long oddShares = shares % SHARES_PER_LOT;
		if (oddShares != 0) {
			reasons.add("含零股 " + oddShares + " 股");
		}
		long buys = trades.stream().filter(trade -> trade.getSide() == TradeSide.BUY).count();
		if (buys > 1) {
			reasons.add("有多次買進");
		}
		if (trades.stream().anyMatch(trade -> trade.getSide() == TradeSide.SELL)) {
			reasons.add("有賣出紀錄");
		}
		return List.copyOf(reasons);
	}

	/** 1.0 可以完整表達這筆持股（整張、只有一筆買進）→ 允許 1.0 修改 */
	public boolean v1Representable() {
		return v1Limits.isEmpty();
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

	/** 總計用的一筆輸入：有盈虧就用計算結果（已捨入到元），沒有就只算總成本 */
	public PortfolioSummary.Line toSummaryLine() {
		return profit != null ? PortfolioSummary.Line.withProfit(profit) : PortfolioSummary.Line.quoteMissing(totalCost);
	}

	/** 四捨五入到元（1.0 的金額一律是整數） */
	private static BigDecimal toYuan(BigDecimal amount) {
		return amount.setScale(0, RoundingMode.HALF_UP);
	}

	private static boolean hasCents(BigDecimal amount) {
		return amount.stripTrailingZeros().scale() > 0;
	}
}
