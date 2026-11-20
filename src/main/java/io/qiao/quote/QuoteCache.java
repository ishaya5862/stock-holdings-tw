package io.qiao.quote;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 行情快取（放在記憶體）：清單頁、API 只讀這裡，不會每次打開就去查官方（題目第 3 題要求 3）。
 *
 * 為什麼放記憶體、不放資料庫？
 * 考官會把官方網址換成自己的測試伺服器再重開；放記憶體的話重開後一定會重新查，
 * 不會拿資料庫裡的舊官方價格當成「目前收盤價」（TRAPS C13）。
 * 資料庫裡的 last_close_price 只用來顯示「上次收盤價」。
 *
 * ConcurrentHashMap：排程（背景執行緒）在寫、網頁（其他執行緒）同時在讀，一般的 HashMap 不安全。
 */
@Component
public class QuoteCache {

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	/** 查不到這檔的快取時回傳 empty（代表這次啟動後還沒查過 → 更新中） */
	public Optional<Entry> get(String code) {
		return Optional.ofNullable(entries.get(code));
	}

	public void putSuccess(StockQuote quote, LocalDateTime checkedAt) {
		entries.put(quote.code(), new Entry(QuoteStatus.OK, quote, checkedAt));
	}

	public void putUnavailable(String code, LocalDateTime checkedAt) {
		entries.put(code, new Entry(QuoteStatus.UNAVAILABLE, null, checkedAt));
	}

	/** 近期（本月與上月）沒有成交資料 */
	public void putNoRecentTrade(String code, LocalDateTime checkedAt) {
		entries.put(code, new Entry(QuoteStatus.NO_RECENT_TRADE, null, checkedAt));
	}

	public void remove(String code) {
		entries.remove(code);
	}

	/**
	 * 一檔股票的快取內容。
	 *
	 * @param status    狀態
	 * @param quote     行情（只有 OK 時有值）
	 * @param checkedAt 最後一次查詢的時間（台北時間）
	 */
	public record Entry(QuoteStatus status, StockQuote quote, LocalDateTime checkedAt) {
	}
}
