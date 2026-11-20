package io.qiao.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteProperties;
import io.qiao.quote.RequestThrottle;
import io.qiao.quote.StockQuote;
import io.qiao.quote.StockQuoteClient;
import io.qiao.quote.TradingCalendar;

/**
 * 背景排程：定時更新每一檔持股的收盤價，清單頁只讀快取（題目第 3 題要求 3、4）。
 *
 * 規則（時間一律台北時間）：
 * - 啟動後立刻查一輪（考官換了網址重開，會馬上去查新的伺服器），之後每輪結束隔 10 分鐘再查。
 * - 只查「需要更新」的股票（needsRefresh）：
 *   還沒查過、上次查詢失敗、或「開盤日 13:30 之後、快取裡還不是今天的收盤價」。
 *   已經拿到今天的收盤價、13:30 前、休市日都不查。
 * - 每一次請求之間間隔 6 秒（RequestThrottle），避免被證交所擋。
 * - 某個市場查詢失敗 → 這一輪不再查那個市場（避免被擋時還繼續猛打），另一個市場照常。
 *
 * 間隔 10 分鐘 + 每輪查詢時間，可保證官方公布後一小時內看得到當天收盤價，不用重開伺服器。
 * 測試時用 app.quote.refresh-enabled=false 關閉，避免測試連到官方。
 */
@Component
@ConditionalOnProperty(name = "app.quote.refresh-enabled", havingValue = "true")
public class QuoteRefreshScheduler {

	private static final Logger log = LoggerFactory.getLogger(QuoteRefreshScheduler.class);

	private final Map<Market, StockQuoteClient> clients;
	private final TradingCalendar tradingCalendar;
	private final QuoteCache quoteCache;
	private final HoldingService holdingService;
	private final QuoteProperties properties;
	private final Clock clock;

	public QuoteRefreshScheduler(
			// 兩個查詢來源都實作 StockQuoteClient，用 @Qualifier 指定 Bean 名稱
			@Qualifier("twseQuoteClient") StockQuoteClient twseClient,
			@Qualifier("tpexQuoteClient") StockQuoteClient tpexClient,
			TradingCalendar tradingCalendar, QuoteCache quoteCache, HoldingService holdingService,
			QuoteProperties properties, Clock clock) {
		this.clients = Map.of(Market.TWSE, twseClient, Market.TPEX, tpexClient);
		this.tradingCalendar = tradingCalendar;
		this.quoteCache = quoteCache;
		this.holdingService = holdingService;
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
		List<Holding> holdings = holdingService.findAll();
		Set<Market> pausedMarkets = EnumSet.noneOf(Market.class);
		Summary summary = new Summary();

		for (Holding holding : holdings) {
			if (Thread.currentThread().isInterrupted()) {
				// 程式關閉中：停止這一輪
				break;
			}
			LocalDateTime now = LocalDateTime.now(clock);
			QuoteCache.Entry entry = quoteCache.get(holding.getCode()).orElse(null);
			if (!needsRefresh(entry, now, throttle)) {
				summary.skipped++;
				continue;
			}
			if (holding.getMarket() == null) {
				refreshUnknownMarket(holding, now, throttle, pausedMarkets, summary);
			} else {
				refreshKnownMarket(holding, now, throttle, pausedMarkets, summary);
			}
		}
		return summary;
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
				return true; // 上次失敗，這輪再試
			case NOT_FOUND:
				return entry.checkedAt().toLocalDate().isBefore(today); // 查無的代號一天重試一次就好
			case OK:
				if (!entry.quote().priceDate().isBefore(today)) {
					return false; // 已經是今天的收盤價
				}
				if (now.toLocalTime().isBefore(properties.marketCloseTime())) {
					return false; // 還沒收盤，昨天（最近交易日）的收盤價就是最新的
				}
				return tradingCalendar.isTradingDay(today, throttle); // 今天有開盤才需要等今天的收盤價
			default:
				return true;
		}
	}

	/** 已知市場：只查那個市場 */
	private void refreshKnownMarket(Holding holding, LocalDateTime now, RequestThrottle throttle,
			Set<Market> pausedMarkets, Summary summary) {
		Market market = holding.getMarket();
		if (pausedMarkets.contains(market)) {
			summary.paused++;
			return;
		}
		try {
			Optional<StockQuote> quote = clients.get(market).fetchLatest(holding.getCode(), throttle);
			if (quote.isPresent()) {
				saveSuccess(holding, quote.get(), now);
				summary.updated++;
			} else {
				quoteCache.putNotFound(holding.getCode(), now);
				summary.failed++;
			}
		} catch (QuoteUnavailableException e) {
			quoteCache.putUnavailable(holding.getCode(), now);
			pausedMarkets.add(market);
			summary.failed++;
			log.warn("{} 查詢失敗，本輪暫停查詢 {}：{}", holding.getCode(), market, e.getMessage());
		}
	}

	/**
	 * 市場未知（第 2 題時新增、還沒有名稱和市場的持股）：先查上市、再查上櫃，查到就補上名稱與市場。
	 * 排程要控制請求間隔，所以依序查（新增持股時才是同時查）。
	 */
	private void refreshUnknownMarket(Holding holding, LocalDateTime now, RequestThrottle throttle,
			Set<Market> pausedMarkets, Summary summary) {
		boolean allConfirmedNotFound = true;
		for (Market market : List.of(Market.TWSE, Market.TPEX)) {
			if (pausedMarkets.contains(market)) {
				allConfirmedNotFound = false;
				continue;
			}
			try {
				Optional<StockQuote> quote = clients.get(market).fetchLatest(holding.getCode(), throttle);
				if (quote.isPresent()) {
					saveSuccess(holding, quote.get(), now);
					summary.updated++;
					return;
				}
			} catch (QuoteUnavailableException e) {
				allConfirmedNotFound = false;
				pausedMarkets.add(market);
				log.warn("{} 查詢失敗，本輪暫停查詢 {}：{}", holding.getCode(), market, e.getMessage());
			}
		}
		// 兩邊都「確定查無」才標記查無；有一邊沒查成，就只是暫時未取得
		if (allConfirmedNotFound) {
			quoteCache.putNotFound(holding.getCode(), now);
		} else {
			quoteCache.putUnavailable(holding.getCode(), now);
		}
		summary.failed++;
	}

	private void saveSuccess(Holding holding, StockQuote quote, LocalDateTime now) {
		quoteCache.putSuccess(quote, now);
		// 寫回資料庫：補名稱與市場、更新「上次收盤價」
		holdingService.recordQuote(holding.getId(), quote);
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
