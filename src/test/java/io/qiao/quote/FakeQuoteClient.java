package io.qiao.quote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.qiao.entity.Market;

/**
 * 測試用的假查詢來源：回傳什麼由 behavior 決定，並記下被查過哪些代號。不連網。
 */
class FakeQuoteClient implements StockQuoteClient {

	private final Market market;
	private final Function<String, Optional<StockQuote>> behavior;
	private final List<String> requestedCodes = Collections.synchronizedList(new ArrayList<>());

	FakeQuoteClient(Market market, Function<String, Optional<StockQuote>> behavior) {
		this.market = market;
		this.behavior = behavior;
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

	/** 被查過的代號（依查詢順序） */
	List<String> requestedCodes() {
		return requestedCodes;
	}
}
