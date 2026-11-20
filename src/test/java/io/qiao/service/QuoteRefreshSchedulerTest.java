package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteProperties;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.RequestThrottle;
import io.qiao.quote.StockQuote;
import io.qiao.quote.StockQuoteClient;
import io.qiao.quote.TradingCalendar;

/**
 * 背景排程的規則測試：什麼時候要查、查失敗怎麼辦、舊資料補名稱。
 * 用假的查詢來源與固定時間的 Clock，不連網、不啟動 Spring。
 */
class QuoteRefreshSchedulerTest {

	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
	private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
	private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 19);

	private final TradingCalendar calendar = mock(TradingCalendar.class);
	private final HoldingService holdingService = mock(HoldingService.class);
	private final QuoteCache cache = new QuoteCache();

	@Test
	void 還沒查過_一定要查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(null, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 收盤前_已有昨天的收盤價_不查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 13, 29), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry(THURSDAY), at(FRIDAY, 13, 29), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 開盤日收盤後_還沒有今天的收盤價_要查() {
		when(calendar.isTradingDay(eq(FRIDAY), any())).thenReturn(true);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 13, 31), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry(THURSDAY), at(FRIDAY, 13, 31), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 已經有今天的收盤價_不查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry(FRIDAY), at(FRIDAY, 15, 0), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 休市日收盤後_不查() {
		when(calendar.isTradingDay(eq(FRIDAY), any())).thenReturn(false);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry(THURSDAY), at(FRIDAY, 15, 0), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 上次查詢失敗_下一輪再試() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry failed = new QuoteCache.Entry(QuoteStatus.UNAVAILABLE, null, at(FRIDAY, 8, 50));

		assertThat(scheduler.needsRefresh(failed, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 近期無成交資料_同一天不重試_隔天再試() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry notFound = new QuoteCache.Entry(QuoteStatus.NO_RECENT_TRADE, null, at(FRIDAY, 8, 0));

		assertThat(scheduler.needsRefresh(notFound, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isFalse();
		assertThat(scheduler.needsRefresh(notFound, at(FRIDAY.plusDays(1), 9, 0), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 一輪更新_查到的放進快取並寫回資料庫() {
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "2330", Market.TWSE)));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330")).get().extracting(QuoteCache.Entry::status).isEqualTo(QuoteStatus.OK);
		verify(holdingService).recordQuote(eq(1L), any(StockQuote.class));
	}

	@Test
	void 某個市場查詢失敗_本輪暫停那個市場_另一個市場照常() {
		when(holdingService.findAll()).thenReturn(List.of(
				holding(1L, "2330", Market.TWSE),
				holding(2L, "2454", Market.TWSE),
				holding(3L, "6488", Market.TPEX)));
		CountingClient twse = new CountingClient(Market.TWSE, code -> {
			throw new QuoteUnavailableException("被擋");
		});
		CountingClient tpex = okClient(Market.TPEX);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		QuoteRefreshScheduler.Summary summary = scheduler.refresh(RequestThrottle.NONE);

		// 證交所第一檔失敗後就不再查 2454；櫃買中心的 6488 照常更新
		assertThat(twse.requested).containsExactly("2330");
		assertThat(tpex.requested).containsExactly("6488");
		assertThat(summary.paused()).isEqualTo(1);
		assertThat(cache.get("2330")).get().extracting(QuoteCache.Entry::status).isEqualTo(QuoteStatus.UNAVAILABLE);
		assertThat(cache.get("2454")).isEmpty(); // 沒查到 → 維持「更新中」
		assertThat(cache.get("6488")).get().extracting(QuoteCache.Entry::status).isEqualTo(QuoteStatus.OK);
	}

	@Test
	void 舊資料沒有市場_先查上市再查上櫃_查到就補上() {
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "6488", null)));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty());
		CountingClient tpex = okClient(Market.TPEX);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(twse.requested).containsExactly("6488");
		assertThat(tpex.requested).containsExactly("6488");
		assertThat(cache.get("6488").get().quote().market()).isEqualTo(Market.TPEX);
		verify(holdingService).recordQuote(eq(1L), any(StockQuote.class));
	}

	@Test
	void 舊資料兩邊都確定近期無成交_標記近期無成交資料() {
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "9999", null)));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0),
				new CountingClient(Market.TWSE, code -> Optional.empty()),
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("9999")).get().extracting(QuoteCache.Entry::status).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
		verify(holdingService, never()).recordQuote(any(), any());
	}

	// ===== 已存在的持股近兩個月都沒有成交資料（下市判斷 A + B） =====

	@Test
	void 原市場查無_另一個市場查到_判斷為轉市場並更新市場別() {
		// 情境：6488 原本記為上櫃，後來轉上市（代號不變）
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "6488", Market.TPEX)));
		CountingClient twse = okClient(Market.TWSE);
		CountingClient tpex = new CountingClient(Market.TPEX, code -> Optional.empty());
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(tpex.requested).containsExactly("6488");
		assertThat(twse.requested).containsExactly("6488");
		assertThat(cache.get("6488").get().status()).isEqualTo(QuoteStatus.OK);
		assertThat(cache.get("6488").get().quote().market()).isEqualTo(Market.TWSE);
		// 寫回資料庫時帶的是「上市」的行情 → recordQuote 會把市場別更新成 TWSE
		verify(holdingService).recordQuote(eq(1L), argThat(quote -> quote.market() == Market.TWSE));
		assertThat(tpex.lastTradeRequested).isEmpty(); // 已經找到，不需要往前多查
	}

	@Test
	void 兩個市場近期都無成交_往前找到最後成交_寫回上次收盤價並標記近期無成交() {
		StockQuote lastTrade = new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("950.00"),
				LocalDate.of(2026, 7, 31));
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "2330", Market.TWSE)));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty(), code -> Optional.of(lastTrade));
		CountingClient tpex = new CountingClient(Market.TPEX, code -> Optional.empty());
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(twse.lastTradeRequested).containsExactly("2330"); // 只往原市場多查
		assertThat(tpex.lastTradeRequested).isEmpty();
		verify(holdingService).recordQuote(1L, lastTrade);
		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
	}

	@Test
	void 往前也找不到成交_標記近期無成交資料_不寫回資料庫() {
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "2330", Market.TWSE)));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0),
				new CountingClient(Market.TWSE, code -> Optional.empty()),
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
		verify(holdingService, never()).recordQuote(any(), any());
	}

	@Test
	void 原市場查無但另一個市場連不上_無法判斷是否轉市場_標記未取得不下結論() {
		when(holdingService.findAll()).thenReturn(List.of(holding(1L, "2330", Market.TWSE)));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty());
		CountingClient tpex = new CountingClient(Market.TPEX, code -> {
			throw new QuoteUnavailableException("連不上");
		});
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.UNAVAILABLE);
		assertThat(twse.lastTradeRequested).isEmpty(); // 沒辦法確認，不往下查
	}

	// ---- 輔助 ----

	private QuoteRefreshScheduler scheduler(LocalDateTime now, StockQuoteClient twse, StockQuoteClient tpex) {
		Clock clock = Clock.fixed(now.atZone(TAIPEI).toInstant(), TAIPEI);
		QuoteProperties properties = new QuoteProperties("http://twse.test", "http://tpex.test", "http://holiday.test", false,
				Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(9),
				true, Duration.ofMinutes(10), Duration.ZERO, LocalTime.of(13, 30), 4);
		return new QuoteRefreshScheduler(twse, tpex, calendar, cache, holdingService, properties, clock);
	}

	private static LocalDateTime at(LocalDate date, int hour, int minute) {
		return date.atTime(hour, minute);
	}

	private static QuoteCache.Entry okEntry(LocalDate priceDate) {
		StockQuote quote = new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("1100"), priceDate);
		return new QuoteCache.Entry(QuoteStatus.OK, quote, priceDate.atTime(14, 0));
	}

	private static CountingClient okClient(Market market) {
		return new CountingClient(market, code -> Optional.of(
				new StockQuote(code, "測試股", market, new BigDecimal("100.00"), THURSDAY)));
	}

	/** 持股的 id 由資料庫產生、沒有 setter，測試用反射設定 */
	private static Holding holding(Long id, String code, Market market) {
		Holding holding = new Holding();
		holding.setCode(code);
		holding.setMarket(market);
		try {
			Field idField = Holding.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(holding, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return holding;
	}

	/**
	 * 假的查詢來源，記下被查過的代號。
	 * behavior：近兩個月（fetchLatest）的回應；lastTrade：往前多查幾個月（fetchLastTrade）的回應，預設找不到。
	 */
	private static final class CountingClient implements StockQuoteClient {
		private final Market market;
		private final Function<String, Optional<StockQuote>> behavior;
		private final Function<String, Optional<StockQuote>> lastTrade;
		private final List<String> requested = new ArrayList<>();
		private final List<String> lastTradeRequested = new ArrayList<>();

		CountingClient(Market market, Function<String, Optional<StockQuote>> behavior) {
			this(market, behavior, code -> Optional.empty());
		}

		CountingClient(Market market, Function<String, Optional<StockQuote>> behavior,
				Function<String, Optional<StockQuote>> lastTrade) {
			this.market = market;
			this.behavior = behavior;
			this.lastTrade = lastTrade;
		}

		@Override
		public Market market() {
			return market;
		}

		@Override
		public Optional<StockQuote> fetchLatest(String code, RequestThrottle throttle) {
			requested.add(code);
			return behavior.apply(code);
		}

		@Override
		public Optional<StockQuote> fetchLastTrade(String code, RequestThrottle throttle, int lookbackMonths) {
			lastTradeRequested.add(code);
			return lastTrade.apply(code);
		}
	}
}
