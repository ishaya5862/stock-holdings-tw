package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteProperties;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.RequestThrottle;
import io.qiao.quote.StockQuote;
import io.qiao.quote.StockQuoteClient;
import io.qiao.quote.TradingCalendar;

/**
 * 背景排程的規則測試：什麼時候要查、查失敗怎麼辦、怎麼避免卡住或查到半夜。
 * 用假的查詢來源與固定時間的 Clock，不連網、不啟動 Spring；寫入股票表的 StockService 換成 mock（假物件），只檢查有沒有被呼叫。
 */
class QuoteRefreshSchedulerTest {

	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
	private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);
	private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 19);

	private final TradingCalendar calendar = mock(TradingCalendar.class);
	private final StockService stockService = mock(StockService.class);
	private final QuoteCache cache = new QuoteCache();

	// ===== 什麼時候要查（needsRefresh） =====

	@Test
	void 還沒查過_一定要查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(null, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 收盤前_已有昨天的收盤價_不查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 13, 29), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry("2330", THURSDAY), at(FRIDAY, 13, 29), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 開盤日收盤後_還沒有今天的收盤價_要查() {
		tradingDay(true);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 13, 31), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry("2330", THURSDAY), at(FRIDAY, 13, 31), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 已經有今天的收盤價_不查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry("2330", FRIDAY), at(FRIDAY, 15, 0), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 休市日收盤後_不查() {
		tradingDay(false);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		assertThat(scheduler.needsRefresh(okEntry("2330", THURSDAY), at(FRIDAY, 15, 0), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 上次查詢失敗_下一輪再試() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry failed = new QuoteCache.Entry(QuoteStatus.UNAVAILABLE, null, at(FRIDAY, 8, 50));

		assertThat(scheduler.needsRefresh(failed, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isTrue();
	}

	// ===== ② 近期無成交資料：快速重查只在開盤日收盤後 =====

	@Test
	void 近期無成交_開盤日收盤後每40分鐘快速重查() {
		tradingDay(true);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 14, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry noTrade = new QuoteCache.Entry(QuoteStatus.NO_RECENT_TRADE, null, at(FRIDAY, 14, 0));

		assertThat(scheduler.needsRefresh(noTrade, at(FRIDAY, 14, 39), RequestThrottle.NONE)).isFalse();
		assertThat(scheduler.needsRefresh(noTrade, at(FRIDAY, 14, 40), RequestThrottle.NONE)).isTrue();
	}

	@Test
	void 近期無成交_收盤前或休市日_不做快速重查_避免半夜週末一直查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry checkedAtNight = new QuoteCache.Entry(QuoteStatus.NO_RECENT_TRADE, null, at(FRIDAY, 0, 30));

		// 收盤前（早上 9 點，距上次檢查已超過 40 分鐘）→ 不查
		assertThat(scheduler.needsRefresh(checkedAtNight, at(FRIDAY, 9, 0), RequestThrottle.NONE)).isFalse();
		// 收盤後但休市日 → 不查
		tradingDay(false);
		assertThat(scheduler.needsRefresh(checkedAtNight, at(FRIDAY, 15, 0), RequestThrottle.NONE)).isFalse();
	}

	@Test
	void 近期無成交_隔天一定做一次完整檢查() {
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));
		QuoteCache.Entry yesterday = new QuoteCache.Entry(QuoteStatus.NO_RECENT_TRADE, null, at(THURSDAY, 20, 0));

		assertThat(scheduler.needsRefresh(yesterday, at(FRIDAY, 0, 10), RequestThrottle.NONE)).isTrue();
	}

	// ===== 一輪更新：查到的寫進快取與股票表（C：只寫 stocks，不碰持股） =====

	@Test
	void 一輪更新_查到的放進快取並寫回股票表() {
		held(stock("2330", Market.TWSE));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), okClient(Market.TWSE), okClient(Market.TPEX));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.OK);
		verify(stockService).recordQuote(argThat(quote -> quote.code().equals("2330")));
	}

	// ===== ① 避免卡住：一直失敗的股票擋不到其他股票 =====

	@Test
	void 某一檔一直失敗_同市場其他股票照常更新() {
		// 1101 每次都失敗（例如考官的測試伺服器只讓它回錯誤），排序在最前面
		held(stock("1101", Market.TWSE), stock("2330", Market.TWSE), stock("2454", Market.TWSE));
		CountingClient twse = new CountingClient(Market.TWSE, code -> {
			if (code.equals("1101")) {
				throw new QuoteUnavailableException("只有 1101 有問題");
			}
			return Optional.of(quote(code, Market.TWSE, THURSDAY));
		});
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, okClient(Market.TPEX));

		scheduler.refresh(RequestThrottle.NONE); // 第 1 輪
		scheduler.refresh(RequestThrottle.NONE); // 第 2 輪：1101 仍失敗

		assertThat(cache.get("1101").get().status()).isEqualTo(QuoteStatus.UNAVAILABLE);
		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.OK);
		assertThat(cache.get("2454").get().status()).isEqualTo(QuoteStatus.OK);
	}

	@Test
	void 上次失敗的股票排到最後才查() {
		held(stock("1101", Market.TWSE), stock("2330", Market.TWSE));
		cache.putUnavailable("1101", at(FRIDAY, 8, 50));
		CountingClient twse = okClient(Market.TWSE);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, okClient(Market.TPEX));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(twse.requested).containsExactly("2330", "1101");
	}

	@Test
	void 同一市場連續兩檔失敗_才暫停這個市場_另一個市場照常() {
		held(stock("2330", Market.TWSE), stock("2454", Market.TWSE), stock("2886", Market.TWSE),
				stock("6488", Market.TPEX));
		CountingClient twse = new CountingClient(Market.TWSE, code -> {
			throw new QuoteUnavailableException("被擋");
		});
		CountingClient tpex = okClient(Market.TPEX);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		QuoteRefreshScheduler.Summary summary = scheduler.refresh(RequestThrottle.NONE);

		// 2330、2454 連續失敗 → 暫停上市，2886 不再查；上櫃的 6488 照常
		assertThat(twse.requested).containsExactly("2330", "2454");
		assertThat(summary.paused()).isEqualTo(1);
		// 2886 沒有查，但這次啟動後從沒查過 → 標記「未取得」，不能停在「更新中…」（第 3 題要求 5）
		// （2026-11-20 修正：原本這裡斷言快取是空的，等於把「一直顯示更新中」當成正確行為）
		assertThat(cache.get("2886").get().status()).isEqualTo(QuoteStatus.UNAVAILABLE);
		assertThat(tpex.requested).containsExactly("6488");
		assertThat(cache.get("6488").get().status()).isEqualTo(QuoteStatus.OK);
	}

	@Test
	void 官方全部連不上_每一檔都顯示未取得_不會停在更新中() {
		// 題目第 3 題要求 5：官方網站連不上時，收盤價那格顯示「未取得」
		held(stock("2330", Market.TWSE), stock("2454", Market.TWSE), stock("2886", Market.TWSE), stock("0050", Market.TWSE),
				stock("6488", Market.TPEX), stock("3105", Market.TPEX), stock("8069", Market.TPEX));
		Function<String, Optional<StockQuote>> down = code -> {
			throw new QuoteUnavailableException("連不上");
		};
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0),
				new CountingClient(Market.TWSE, down), new CountingClient(Market.TPEX, down));

		scheduler.refresh(RequestThrottle.NONE);

		for (String code : List.of("2330", "2454", "2886", "0050", "6488", "3105", "8069")) {
			assertThat(cache.get(code)).as(code + " 不能停在更新中（快取沒有紀錄）").isPresent();
			assertThat(cache.get(code).get().status()).as(code).isEqualTo(QuoteStatus.UNAVAILABLE);
		}
	}

	@Test
	void 市場暫停時_之前查到的收盤價不會被蓋成未取得() {
		tradingDay(true);
		held(stock("2330", Market.TWSE), stock("2454", Market.TWSE), stock("2886", Market.TWSE));
		// 2886 早上已經查到昨天的收盤價；收盤後要查今天的，但官方此時連不上
		cache.putSuccess(quote("2886", Market.TWSE, THURSDAY), at(FRIDAY, 9, 0));
		CountingClient twse = new CountingClient(Market.TWSE, code -> {
			throw new QuoteUnavailableException("連不上");
		});
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 14, 0), twse, okClient(Market.TPEX));

		scheduler.refresh(RequestThrottle.NONE);

		// 2330、2454 先查（還沒查過的排前面）、連續失敗 → 暫停上市；2886 被略過，保留昨天的收盤價
		assertThat(twse.requested).containsExactly("2330", "2454");
		assertThat(cache.get("2886").get().status()).isEqualTo(QuoteStatus.OK);
		assertThat(cache.get("2886").get().quote().priceDate()).isEqualTo(THURSDAY);
	}

	// ===== ③ 避免查到半夜：16:00 之後連續 3 次拿到舊日期，今天就不再等 =====

	@Test
	void 十六點後連續3次都不是今天的收盤價_今天不再查() {
		tradingDay(true);
		held(stock("2330", Market.TWSE));
		CountingClient twse = okClient(Market.TWSE); // 一直回傳 11/19（例如今天臨時休市）
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 16, 30), twse, okClient(Market.TPEX));

		for (int i = 0; i < 5; i++) {
			scheduler.refresh(RequestThrottle.NONE);
		}

		assertThat(twse.requested).hasSize(3); // 第 4、5 輪不再查
	}

	@Test
	void 十六點前拿到舊日期不算_官方可能還沒公布_照常每輪查() {
		tradingDay(true);
		held(stock("2330", Market.TWSE));
		CountingClient twse = okClient(Market.TWSE);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 14, 30), twse, okClient(Market.TPEX));

		for (int i = 0; i < 5; i++) {
			scheduler.refresh(RequestThrottle.NONE);
		}

		assertThat(twse.requested).hasSize(5);
	}

	// ===== 市場未知的舊資料 =====

	@Test
	void 舊資料沒有市場_先查上市再查上櫃_查到就補上() {
		held(stock("6488", null));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty());
		CountingClient tpex = okClient(Market.TPEX);
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(twse.requested).containsExactly("6488");
		assertThat(tpex.requested).containsExactly("6488");
		assertThat(cache.get("6488").get().quote().market()).isEqualTo(Market.TPEX);
		verify(stockService).recordQuote(argThat(quote -> quote.market() == Market.TPEX));
	}

	@Test
	void 舊資料兩邊都確定近期無成交_標記近期無成交資料() {
		held(stock("9999", null));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0),
				new CountingClient(Market.TWSE, code -> Optional.empty()),
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("9999").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
		verify(stockService, never()).recordQuote(any());
	}

	// ===== 已存在的持股近兩個月都沒有成交資料（下市判斷 A + B） =====

	@Test
	void 原市場查無_另一個市場查到_判斷為轉市場並更新市場別() {
		held(stock("6488", Market.TPEX)); // 原本記為上櫃，後來轉上市（代號不變）
		CountingClient twse = okClient(Market.TWSE);
		CountingClient tpex = new CountingClient(Market.TPEX, code -> Optional.empty());
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("6488").get().status()).isEqualTo(QuoteStatus.OK);
		assertThat(cache.get("6488").get().quote().market()).isEqualTo(Market.TWSE);
		verify(stockService).recordQuote(argThat(quote -> quote.market() == Market.TWSE));
		assertThat(tpex.lastTradeRequested).isEmpty();
	}

	@Test
	void 兩個市場近期都無成交_往前找到最後成交_寫回股票表並標記近期無成交() {
		StockQuote lastTrade = new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("950.00"), LocalDate.of(2026, 7, 31));
		held(stock("2330", Market.TWSE));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty(), code -> Optional.of(lastTrade));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse,
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(twse.lastTradeRequested).containsExactly("2330");
		verify(stockService).recordQuote(lastTrade);
		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
	}

	@Test
	void 往前也找不到成交_標記近期無成交資料_不寫回() {
		held(stock("2330", Market.TWSE));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0),
				new CountingClient(Market.TWSE, code -> Optional.empty()),
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);
		verify(stockService, never()).recordQuote(any());
	}

	@Test
	void 原市場查無但另一個市場連不上_標記未取得不下結論() {
		held(stock("2330", Market.TWSE));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty());
		CountingClient tpex = new CountingClient(Market.TPEX, code -> {
			throw new QuoteUnavailableException("連不上");
		});
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 9, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE);

		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.UNAVAILABLE);
		assertThat(twse.lastTradeRequested).isEmpty();
	}

	@Test
	void 近期無成交_同一天第二次只做快速重查_不再查另一個市場和往前幾個月() {
		tradingDay(true);
		held(stock("2330", Market.TWSE));
		CountingClient twse = new CountingClient(Market.TWSE, code -> Optional.empty());
		CountingClient tpex = new CountingClient(Market.TPEX, code -> Optional.empty());
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), twse, tpex);

		scheduler.refresh(RequestThrottle.NONE); // 第一次：完整檢查
		cache.putNoRecentTrade("2330", at(FRIDAY, 14, 20)); // 模擬已過 40 分鐘
		scheduler.refresh(RequestThrottle.NONE); // 第二次（同一天）：快速重查

		assertThat(twse.requested).hasSize(2);
		assertThat(tpex.requested).hasSize(1);
		assertThat(twse.lastTradeRequested).hasSize(1);
	}

	@Test
	void 近期無成交_快速重查時有成交了_立刻恢復正常() {
		tradingDay(true);
		held(stock("2330", Market.TWSE));
		List<Optional<StockQuote>> answers = new ArrayList<>(List.of(Optional.empty(),
				Optional.of(quote("2330", Market.TWSE, FRIDAY))));
		CountingClient twse = new CountingClient(Market.TWSE, code -> answers.remove(0));
		QuoteRefreshScheduler scheduler = scheduler(at(FRIDAY, 15, 0), twse,
				new CountingClient(Market.TPEX, code -> Optional.empty()));

		scheduler.refresh(RequestThrottle.NONE);
		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.NO_RECENT_TRADE);

		cache.putNoRecentTrade("2330", at(FRIDAY, 14, 20));
		scheduler.refresh(RequestThrottle.NONE);
		assertThat(cache.get("2330").get().status()).isEqualTo(QuoteStatus.OK);
	}

	// ---- 輔助 ----

	private QuoteRefreshScheduler scheduler(LocalDateTime now, StockQuoteClient twse, StockQuoteClient tpex) {
		Clock clock = Clock.fixed(now.atZone(TAIPEI).toInstant(), TAIPEI);
		QuoteProperties properties = new QuoteProperties("http://twse.test", "http://tpex.test", "http://holiday.test", false,
				Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(9),
				true, Duration.ofMinutes(10), Duration.ZERO, LocalTime.of(13, 30), 4, LocalTime.of(16, 0));
		return new QuoteRefreshScheduler(twse, tpex, calendar, cache, stockService, properties, clock);
	}

	private void tradingDay(boolean open) {
		when(calendar.isTradingDay(eq(FRIDAY), any())).thenReturn(open);
	}

	private void held(Stock... stocks) {
		when(stockService.findHeld()).thenReturn(List.of(stocks));
	}

	private static Stock stock(String code, Market market) {
		return new Stock(code, market == null ? null : "測試股", market, null, null, LocalDateTime.of(2026, 11, 20, 0, 0));
	}

	private static LocalDateTime at(LocalDate date, int hour, int minute) {
		return date.atTime(hour, minute);
	}

	private static StockQuote quote(String code, Market market, LocalDate date) {
		return new StockQuote(code, "測試股", market, new BigDecimal("100.00"), date);
	}

	private static QuoteCache.Entry okEntry(String code, LocalDate priceDate) {
		return new QuoteCache.Entry(QuoteStatus.OK, quote(code, Market.TWSE, priceDate), priceDate.atTime(14, 0));
	}

	/** 永遠回傳 11/19（昨天）收盤價的查詢來源 */
	private static CountingClient okClient(Market market) {
		return new CountingClient(market, code -> Optional.of(quote(code, market, THURSDAY)));
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

		@Override
		public Optional<StockQuote> fetchInMonth(String code, YearMonth month) {
			return Optional.empty(); // 排程不使用（只有新增持股補登舊交易時用）
		}
	}
}
