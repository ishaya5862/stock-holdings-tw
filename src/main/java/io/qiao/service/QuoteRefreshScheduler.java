package io.qiao.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteProperties;
import io.qiao.quote.RequestThrottle;
import io.qiao.quote.StockQuote;
import io.qiao.quote.StockQuoteClient;
import io.qiao.quote.TradingCalendar;

/**
 * 背景排程：定時更新「有人持有的股票」的收盤價，清單頁只讀快取（題目第 3 題要求 3、4）。
 * 寫入一律透過 StockService（只寫 stocks 表），不碰持股（holdings），不會和使用者的修改互相覆寫。
 *
 * 規則（時間一律台北時間）：
 * - 啟動後立刻查一輪（考官換了網址重開，會馬上去查新的伺服器），之後每輪結束隔 10 分鐘再查。
 * - 只查「需要更新」的股票（needsRefresh）：還沒查過、上次查詢失敗、或「開盤日 13:30 之後、還不是今天的收盤價」。
 * - 每一次請求之間間隔 6 秒（RequestThrottle），避免被證交所擋。
 * - 【避免卡住】查詢順序：還沒查過 → 正常要更新 → 近期無成交 → 上次失敗，「上次失敗」排最後，
 *   某一檔一直失敗也擋不到其他股票。同一個市場「連續 2 檔」失敗才暫停這個市場（真的被擋時才停）。
 * - 【避免查到半夜】16:00 之後同一檔連續 3 次查到的都不是今天的收盤價（颱風假、當天停止交易），今天就不再查它。
 * - 已存在的持股近兩個月都沒有成交資料：
 *   完整檢查（每檔每天一次）：改查另一個市場（轉上市／轉上櫃）→ 往前多查幾個月找最後成交 → 標記「近期無成交資料」；
 *   快速重查（開盤日 13:30 後、每 40 分鐘）：只查原市場近兩個月，一有成交就恢復正常。
 *
 * 測試時用 app.quote.refresh-enabled=false 關閉，避免測試連到官方。
 */
@Component
@ConditionalOnProperty(name = "app.quote.refresh-enabled", havingValue = "true")
public class QuoteRefreshScheduler {

	private static final Logger log = LoggerFactory.getLogger(QuoteRefreshScheduler.class);

	/**
	 * 「近期無成交資料」的快速重查間隔。
	 * 40 分鐘的原因：40 ＋ 排程間隔 10 分鐘 ＋ 一輪查詢時間 ≤ 60 分鐘，才能保證「官方公布後一小時內看得到」。
	 */
	static final Duration NO_TRADE_QUICK_RECHECK = Duration.ofMinutes(40);

	/** 同一個市場連續幾檔失敗才暫停這個市場（只有一檔有問題時不影響其他股票） */
	static final int FAILURES_TO_PAUSE_MARKET = 2;

	/** staleGiveUpAfter 之後，同一檔連續幾次查到舊日期就今天放棄 */
	static final int STALE_TRIES_TO_GIVE_UP = 3;

	/** 每檔股票最後一次「完整檢查」（另一個市場＋往前多查幾個月）的日期；完整檢查每天最多一次 */
	private final Map<String, LocalDate> fullCheckDates = new ConcurrentHashMap<>();

	/** 每檔股票今天「查到的仍是舊日期」的次數（16:00 之後才計算） */
	private final Map<String, StaleCount> staleCounts = new ConcurrentHashMap<>();

	private final Map<Market, StockQuoteClient> clients;
	private final TradingCalendar tradingCalendar;
	private final QuoteCache quoteCache;
	private final StockService stockService;
	private final QuoteProperties properties;
	private final Clock clock;

	public QuoteRefreshScheduler(
			// 兩個查詢來源都實作 StockQuoteClient，用 @Qualifier 指定 Bean 名稱
			@Qualifier("twseQuoteClient") StockQuoteClient twseClient,
			@Qualifier("tpexQuoteClient") StockQuoteClient tpexClient,
			TradingCalendar tradingCalendar, QuoteCache quoteCache, StockService stockService,
			QuoteProperties properties, Clock clock) {
		this.clients = Map.of(Market.TWSE, twseClient, Market.TPEX, tpexClient);
		this.tradingCalendar = tradingCalendar;
		this.quoteCache = quoteCache;
		this.stockService = stockService;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * fixedDelay：上一輪「結束後」才開始計時，一輪查很久也不會和下一輪重疊。
	 * initialDelay = 0：程式啟動後立刻執行第一輪。
	 */
	@Scheduled(fixedDelayString = "${app.quote.refresh-interval}", initialDelay = 0)
	public void scheduledRefresh() {
		Summary summary = refresh(new RequestThrottle(properties.requestGap()));
		log.info("行情更新完成：更新 {} 檔、不需更新 {} 檔、失敗 {} 檔、因市場暫停略過 {} 檔",
				summary.updated(), summary.skipped(), summary.failed(), summary.paused());
	}

	/**
	 * 執行一輪更新（測試會直接呼叫，傳入不等待的 throttle）。
	 */
	Summary refresh(RequestThrottle throttle) {
		List<Stock> stocks = new ArrayList<>(stockService.findHeld());
		// 【避免卡住】依狀態排序（同狀態維持代號順序）：上次失敗的排最後，一直失敗的股票擋不到其他股票
		stocks.sort(Comparator.comparingInt(stock -> priority(quoteCache.get(stock.getCode()).orElse(null))));

		Round round = new Round();
		for (Stock stock : stocks) {
			if (Thread.currentThread().isInterrupted()) {
				break; // 程式關閉中：停止這一輪
			}
			LocalDateTime now = LocalDateTime.now(clock);
			QuoteCache.Entry entry = quoteCache.get(stock.getCode()).orElse(null);
			if (!needsRefresh(entry, now, throttle)) {
				round.summary.skipped++;
				continue;
			}
			if (stock.getMarket() == null) {
				refreshUnknownMarket(stock.getCode(), now, throttle, round);
			} else {
				refreshKnownMarket(stock.getCode(), stock.getMarket(), now, throttle, round);
			}
		}
		return round.summary;
	}

	/** 查詢順序：還沒查過 → 正常 → 近期無成交 → 上次失敗 */
	private static int priority(QuoteCache.Entry entry) {
		if (entry == null) {
			return 0;
		}
		return switch (entry.status()) {
			case OK -> 1;
			case NO_RECENT_TRADE -> 2;
			case UNAVAILABLE -> 3;
			default -> 0;
		};
	}

	/**
	 * 這一檔需不需要向官方查詢。先判斷不用連網的條件，最後才查休市日（可能要發請求）。
	 */
	boolean needsRefresh(QuoteCache.Entry entry, LocalDateTime now, RequestThrottle throttle) {
		if (entry == null) {
			return true; // 這次啟動後還沒查過
		}
		LocalDate today = now.toLocalDate();
		switch (entry.status()) {
			case UNAVAILABLE:
				return true; // 上次失敗，這輪再試（排在最後，不會擋到其他股票）
			case NO_RECENT_TRADE:
				if (entry.checkedAt().toLocalDate().isBefore(today)) {
					return true; // 今天還沒檢查過 → 做一次完整檢查
				}
				// 快速重查：新的成交資料只會在開盤日收盤後出現，半夜、週末、休市日查了也一定沒有
				return !now.isBefore(entry.checkedAt().plus(NO_TRADE_QUICK_RECHECK))
						&& afterMarketClose(now)
						&& tradingCalendar.isTradingDay(today, throttle);
			case OK:
				if (!entry.quote().priceDate().isBefore(today)) {
					return false; // 已經是今天的收盤價
				}
				if (!afterMarketClose(now)) {
					return false; // 還沒收盤，最近一個交易日的收盤價就是最新的
				}
				if (gaveUpToday(entry.quote().code(), today)) {
					return false; // 今天已經確定等不到（颱風假、當天停止交易）
				}
				return tradingCalendar.isTradingDay(today, throttle); // 今天有開盤才需要等今天的收盤價
			default:
				return true;
		}
	}

	/** 已知市場：先查原本的市場；近兩個月都沒有成交資料時，交給 handleNoRecentTrade 進一步判斷 */
	private void refreshKnownMarket(String code, Market market, LocalDateTime now, RequestThrottle throttle, Round round) {
		if (round.paused.contains(market)) {
			round.summary.paused++;
			return;
		}
		try {
			Optional<StockQuote> quote = clients.get(market).fetchLatest(code, throttle);
			round.succeeded(market);
			if (quote.isPresent()) {
				saveSuccess(quote.get(), now);
				round.summary.updated++;
				return;
			}
		} catch (QuoteUnavailableException e) {
			markUnavailable(code, market, now, round, e);
			return;
		}

		// 原市場近兩個月沒有成交資料
		if (now.toLocalDate().equals(fullCheckDates.get(code))) {
			// 今天已經做過完整檢查：這次只是快速重查，仍然沒有資料 → 維持「近期無成交資料」，更新檢查時間
			quoteCache.putNoRecentTrade(code, now);
			round.summary.failed++;
			return;
		}
		handleNoRecentTrade(code, market, now, throttle, round);
	}

	/**
	 * 已存在的持股在原市場近兩個月都沒有成交資料（完整檢查）：
	 * 1. 改查另一個市場：查到 → 上櫃轉上市（或反過來），代號不變、市場改變，股票表的市場別跟著更新。
	 * 2. 原市場往前多查幾個月：找到最後一次成交 → 寫回股票表的「上次收盤價」（長期停止交易）。
	 * 3. 標記「近期無成交資料（可能停止交易或已下市）」。
	 * 任何一步連不上 → 標記「未取得」，下一輪再試（無法判斷時不下結論）。
	 */
	private void handleNoRecentTrade(String code, Market market, LocalDateTime now, RequestThrottle throttle, Round round) {
		// 1. 另一個市場
		Market otherMarket = market == Market.TWSE ? Market.TPEX : Market.TWSE;
		if (round.paused.contains(otherMarket)) {
			// 另一個市場這一輪暫停中，無法確認是否轉市場，先不下結論
			quoteCache.putUnavailable(code, now);
			round.summary.paused++;
			return;
		}
		try {
			Optional<StockQuote> moved = clients.get(otherMarket).fetchLatest(code, throttle);
			round.succeeded(otherMarket);
			if (moved.isPresent()) {
				log.info("{} 在 {} 查不到、在 {} 查到，市場別更新為 {}（轉市場）", code, market, otherMarket, otherMarket);
				saveSuccess(moved.get(), now);
				round.summary.updated++;
				return;
			}
		} catch (QuoteUnavailableException e) {
			markUnavailable(code, otherMarket, now, round, e);
			return;
		}

		// 2. 原市場往前多查幾個月，找最後一次成交
		try {
			Optional<StockQuote> lastTrade = clients.get(market).fetchLastTrade(code, throttle, properties.lookbackMonths());
			lastTrade.ifPresent(quote -> {
				log.info("{} 近兩個月無成交，最後一次成交在 {}（收盤 {}）", code, quote.priceDate(), quote.closePrice());
				// 寫回股票表的「上次收盤價」：畫面會顯示「最後收盤價 X（日期）」
				stockService.recordQuote(quote);
			});
		} catch (QuoteUnavailableException e) {
			markUnavailable(code, market, now, round, e);
			return;
		}

		// 3. 近期無成交資料；記下「今天已完整檢查過」，之後當天只做快速重查
		fullCheckDates.put(code, now.toLocalDate());
		quoteCache.putNoRecentTrade(code, now);
		round.summary.failed++;
	}

	/**
	 * 市場未知（第 2 題時新增、還沒有名稱和市場的持股）：先查上市、再查上櫃，查到就補上名稱與市場。
	 * 排程要控制請求間隔，所以依序查（新增持股時才是同時查）。
	 */
	private void refreshUnknownMarket(String code, LocalDateTime now, RequestThrottle throttle, Round round) {
		boolean allConfirmedNoTrade = true;
		for (Market market : List.of(Market.TWSE, Market.TPEX)) {
			if (round.paused.contains(market)) {
				allConfirmedNoTrade = false;
				continue;
			}
			try {
				Optional<StockQuote> quote = clients.get(market).fetchLatest(code, throttle);
				round.succeeded(market);
				if (quote.isPresent()) {
					saveSuccess(quote.get(), now);
					round.summary.updated++;
					return;
				}
			} catch (QuoteUnavailableException e) {
				allConfirmedNoTrade = false;
				round.failed(market);
				log.warn("{} 查詢失敗（{}）：{}", code, market, e.getMessage());
			}
		}
		// 兩邊都「確定近期無成交」才標記；有一邊沒查成，就只是暫時未取得
		if (allConfirmedNoTrade) {
			quoteCache.putNoRecentTrade(code, now);
		} else {
			quoteCache.putUnavailable(code, now);
		}
		round.summary.failed++;
	}

	/** 查詢失敗：這一檔標記「未取得」；同一市場連續失敗達門檻才暫停這個市場 */
	private void markUnavailable(String code, Market market, LocalDateTime now, Round round, QuoteUnavailableException e) {
		quoteCache.putUnavailable(code, now);
		round.summary.failed++;
		boolean paused = round.failed(market);
		log.warn("{} 查詢失敗（{}）{}：{}", code, market, paused ? "，本輪暫停查詢這個市場" : "", e.getMessage());
	}

	private void saveSuccess(StockQuote quote, LocalDateTime now) {
		quoteCache.putSuccess(quote, now);
		// 寫回股票表：名稱、市場、上次收盤價（只寫 stocks，不碰持股）
		stockService.recordQuote(quote);
		trackStale(quote, now);
	}

	/**
	 * 【避免查到半夜】16:00 之後，查成功但拿到的仍不是今天的收盤價 → 次數 +1；達 3 次今天就不再等。
	 * 拿到今天的收盤價就清掉紀錄。
	 */
	private void trackStale(StockQuote quote, LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		if (!quote.priceDate().isBefore(today)) {
			staleCounts.remove(quote.code());
			return;
		}
		if (now.toLocalTime().isBefore(properties.staleGiveUpAfter())) {
			return; // 還早，官方可能還沒公布
		}
		staleCounts.merge(quote.code(), new StaleCount(today, 1),
				(old, one) -> old.date().equals(today) ? new StaleCount(today, old.count() + 1) : one);
		if (gaveUpToday(quote.code(), today)) {
			log.info("{} 在 {} 之後連續 {} 次仍不是今天的收盤價（可能臨時休市或當天停止交易），今天不再查",
					quote.code(), properties.staleGiveUpAfter(), STALE_TRIES_TO_GIVE_UP);
		}
	}

	private boolean gaveUpToday(String code, LocalDate today) {
		StaleCount count = staleCounts.get(code);
		return count != null && count.date().equals(today) && count.count() >= STALE_TRIES_TO_GIVE_UP;
	}

	private boolean afterMarketClose(LocalDateTime now) {
		return !now.toLocalTime().isBefore(properties.marketCloseTime());
	}

	/** 某檔股票某一天「查到舊日期」的次數 */
	private record StaleCount(LocalDate date, int count) {
	}

	/** 一輪內的狀態：各市場連續失敗次數、暫停中的市場、統計 */
	private static final class Round {
		private final Map<Market, Integer> consecutiveFailures = new EnumMap<>(Market.class);
		private final Set<Market> paused = EnumSet.noneOf(Market.class);
		private final Summary summary = new Summary();

		/** 這個市場有正常回應（包含「查無」）：連續失敗歸零 */
		void succeeded(Market market) {
			consecutiveFailures.remove(market);
		}

		/** 這個市場失敗一次；連續達門檻就暫停。回傳是否已暫停 */
		boolean failed(Market market) {
			int count = consecutiveFailures.merge(market, 1, Integer::sum);
			if (count >= FAILURES_TO_PAUSE_MARKET) {
				paused.add(market);
			}
			return paused.contains(market);
		}
	}

	/** 一輪的統計（寫 log 用，測試也用來檢查） */
	static final class Summary {
		int updated;
		int skipped;
		int failed;
		int paused;

		int updated() {
			return updated;
		}

		int skipped() {
			return skipped;
		}

		int failed() {
			return failed;
		}

		int paused() {
			return paused;
		}
	}
}
