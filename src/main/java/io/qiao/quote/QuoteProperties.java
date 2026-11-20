package io.qiao.quote;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 官方行情的設定（application.properties 裡 app.quote.* 開頭的值）。
 *
 * 用 @ConfigurationProperties 把一組設定綁成一個物件：
 * 比到處寫 @Value("${...}") 好管理，也能直接寫 3s 這種時間格式（自動轉成 Duration）。
 *
 * @param twseBaseUrl    證交所主機（例：https://www.twse.com.tw），可用環境變數 TWSE_BASE_URL 改掉
 * @param tpexBaseUrl    櫃買中心主機（例：https://www.tpex.org.tw），可用環境變數 TPEX_BASE_URL 改掉
 * @param connectTimeout 連線逾時
 * @param readTimeout    讀取逾時
 */
@ConfigurationProperties(prefix = "app.quote")
public record QuoteProperties(
		String twseBaseUrl,
		String tpexBaseUrl,
		Duration connectTimeout,
		Duration readTimeout) {
}
