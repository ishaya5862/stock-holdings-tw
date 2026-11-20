package io.qiao.quote;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.qiao.entity.Market;

/**
 * 測試用的假查詢來源：回傳什麼由 behavior 決定，並記下被查過哪些代號。不連網。
 */
class FakeQuoteClient implements StockQuoteClient {

	private final Market market;
	private final Function<String, Optional<StockQuote>> behavior;
	private final List<String> requestedCodes = Collections.synchronizedList(new ArrayList<>());

	/** 查某一個月（fetchInMonth）的結果；預設「查無」 */
	private BiFunction<String, YearMonth, Optional<StockQuote>> monthBehavior = (code, month) -> Optional.empty();
	private final List<YearMonth> requestedMonths = Collections.synchronizedList(new ArrayList<>());
	private YearMonth earliestMonth = YearMonth.of(1990, 1);

	FakeQuoteClient(Market market, Function<String, Optional<StockQuote>> behavior) {
		this.market = market;
		this.behavior = behavior;
	}

	FakeQuoteClient withMonthBehavior(BiFunction<String, YearMonth, Optional<StockQuote>> monthBehavior) {
		this.monthBehavior = monthBehavior;
		return this;
	}

	/** 模擬證交所：更早的月份沒有資料（不送出查詢） */
	FakeQuoteClient withEarliestMonth(YearMonth earliestMonth) {
		this.earliestMonth = earliestMonth;
		return this;
	}

	@Override
	public Market market() {
		return market;
	}

	@Override
	public Optional<StockQuote> fetchLatest(String code, RequestThrottle throttle) {
		requestedCodes.add(code);
		return behavior.apply(code);
	}

	@Override
	public Optional<StockQuote> fetchLastTrade(String code, RequestThrottle throttle, int lookbackMonths) {
		return Optional.empty();
	}

	@Override
	public Optional<StockQuote> fetchInMonth(String code, YearMonth month) {
		requestedMonths.add(month);
		return monthBehavior.apply(code, month);
	}

	@Override
	public YearMonth earliestMonth() {
		return earliestMonth;
	}

	/** 被查過的代號（依查詢順序） */
	List<String> requestedCodes() {
		return requestedCodes;
	}

	/** fetchInMonth 被查過的月份 */
	List<YearMonth> requestedMonths() {
		return requestedMonths;
	}
}
