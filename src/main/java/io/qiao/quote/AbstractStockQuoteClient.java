package io.qiao.quote;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.qiao.exception.QuoteUnavailableException;

/**
 * 證交所、櫃買中心共用的查詢流程。兩邊不同的只有「網址怎麼組」和「回應怎麼讀」，由子類別實作。
 *
 * 共用流程：
 * 1. 查「本月」（台北時間），有資料就取最後一個有收盤價的交易日。
 * 2. 本月沒有資料（月初、或每月第一個交易日收盤前）→ 再查「上個月」。
 *    少了這一步，每月第一個交易日收盤前，所有股票都會被誤判成「不存在」。
 * 3. 兩個月都沒有 → 這個市場沒有這檔股票。
 */
abstract class AbstractStockQuoteClient implements StockQuoteClient {

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	protected AbstractStockQuoteClient(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override
	public Optional<StockQuote> fetchLatest(String code, RequestThrottle throttle) {
		YearMonth thisMonth = YearMonth.now(clock);
		for (YearMonth month : List.of(thisMonth, thisMonth.minusMonths(1))) {
			// 每一次請求前都先經過 throttle（排程時同一檔的「本月」「上個月」兩次請求也會間隔開）
			throttle.beforeRequest();
			MonthlyQuotes monthly = fetchMonth(code, month);
			if (!monthly.dailyCloses().isEmpty()) {
				// 清單已依日期排序，最後一筆就是「最近一個交易日」
				DailyClose latest = monthly.dailyCloses().get(monthly.dailyCloses().size() - 1);
				return Optional.of(new StockQuote(code, monthly.name(), market(), latest.closePrice(), latest.date()));
			}
		}
		return Optional.empty();
	}

	/**
	 * 查某個月的行情。
	 *
	 * @return 這個月的名稱與每日收盤價；查無資料時 dailyCloses 為空清單
	 */
	protected abstract MonthlyQuotes fetchMonth(String code, YearMonth month);

	/**
	 * 發出 GET 請求並把回應讀成 JSON 樹（JsonNode）。
	 * 所有「無法判斷」的錯誤都在這裡統一轉成 QuoteUnavailableException。
	 */
	protected JsonNode getJson(Function<UriBuilder, URI> uriFunction) {
		try {
			// 先拿原始位元組再交給 Jackson 解析，不依賴對方回傳的 Content-Type 和編碼設定
			// （若回應沒標明 UTF-8，用字串接會變成亂碼）
			byte[] body = restClient.get().uri(uriFunction).retrieve().body(byte[].class);
			if (body == null || body.length == 0) {
				throw new QuoteUnavailableException(market() + " 回應是空的");
			}
			return objectMapper.readTree(body);
		} catch (RestClientException e) {
			// 連不上、逾時、HTTP 4xx / 5xx
			throw new QuoteUnavailableException(market() + " 連線失敗：" + e.getMessage(), e);
		} catch (IOException e) {
			// 回應不是 JSON（例如被擋時回傳的 HTML 頁面）
			throw new QuoteUnavailableException(market() + " 回應不是 JSON：" + e.getMessage(), e);
		}
	}

	/**
	 * 把解析過程中的格式錯誤也轉成「無法判斷」。
	 * NullPointerException：回應少了預期的節點（例如某一列欄位數不足），一樣視為格式不正確。
	 */
	protected MonthlyQuotes parseSafely(Supplier<MonthlyQuotes> parser) {
		try {
			return parser.get();
		} catch (QuoteFormatException | NullPointerException e) {
			throw new QuoteUnavailableException(market() + " 回應格式不正確：" + e.getMessage(), e);
		}
	}

	/**
	 * 某個月的查詢結果。
	 *
	 * @param name        股票名稱（查無資料時可能是空字串）
	 * @param dailyCloses 每日收盤價，依日期由舊到新；查無資料時為空清單
	 */
	protected record MonthlyQuotes(String name, List<DailyClose> dailyCloses) {

		static MonthlyQuotes empty() {
			return new MonthlyQuotes("", List.of());
		}
	}
}
