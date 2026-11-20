package io.qiao.quote;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 官方行情的設定（application.properties 裡 app.quote.* 開頭的值）。
 *
 * 用 @ConfigurationProperties 把一組設定綁成一個物件：
 * 比到處寫 @Value("${...}") 好管理，也能直接寫 3s、10m 這種時間格式（自動轉成 Duration）。
 *
 * @param twseBaseUrl     證交所主機（例：https://www.twse.com.tw），可用環境變數 TWSE_BASE_URL 改掉
 * @param tpexBaseUrl     櫃買中心主機（例：https://www.tpex.org.tw），可用環境變數 TPEX_BASE_URL 改掉
 * @param holidayBaseUrl  休市日行事曆的主機（預設真正的證交所，不跟 TWSE_BASE_URL），可用環境變數 HOLIDAY_BASE_URL 改掉
 * @param holidayApiEnabled 是否查官方休市日 API（false = 不對外連線，只用內附檔），環境變數 HOLIDAY_API_ENABLED
 * @param connectTimeout  連線逾時
 * @param readTimeout     讀取逾時
 * @param lookupTimeout   新增持股時「判斷上市／上櫃」的整體時間上限（題目要求 10 秒內有結果）
 * @param refreshEnabled  是否啟用背景排程（測試時關閉，避免連到官方）
 * @param refreshInterval 背景排程：每一輪結束後隔多久再查下一輪
 * @param requestGap      背景排程：每一次請求之間至少間隔多久（避免被證交所擋）
 * @param marketCloseTime 收盤時間（台北時間）：這個時間之後才開始等「今天的收盤價」
 * @param lookbackMonths  已存在的持股近兩個月都查不到時，再往前查幾個月找「最後一次成交」
 * @param staleGiveUpAfter 這個時間（台北時間）之後，同一檔連續 3 次查到的都不是今天的收盤價，今天就不再等（颱風假、當天停止交易）
 */
@ConfigurationProperties(prefix = "app.quote")
public record QuoteProperties(
		String twseBaseUrl,
		String tpexBaseUrl,
		String holidayBaseUrl,
		boolean holidayApiEnabled,
		Duration connectTimeout,
		Duration readTimeout,
		Duration lookupTimeout,
		boolean refreshEnabled,
		Duration refreshInterval,
		Duration requestGap,
		LocalTime marketCloseTime,
		int lookbackMonths,
		LocalTime staleGiveUpAfter) {
}
