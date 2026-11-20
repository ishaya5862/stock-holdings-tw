package io.qiao.quote;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 建立呼叫證交所、櫃買中心用的 RestClient。
 */
@Configuration
@EnableConfigurationProperties(QuoteProperties.class)
public class QuoteConfig {

	/** 有些網站會擋沒有 User-Agent 或看起來像程式的請求，帶上一個明確的名稱 */
	private static final String USER_AGENT = "Mozilla/5.0 (compatible; StockHoldingsLedger/1.0)";

	@Bean
	public TwseQuoteClient twseQuoteClient(QuoteProperties properties, ObjectMapper objectMapper, Clock clock) {
		return new TwseQuoteClient(restClient(properties.twseBaseUrl(), properties), objectMapper, clock);
	}

	@Bean
	public TpexQuoteClient tpexQuoteClient(QuoteProperties properties, ObjectMapper objectMapper, Clock clock) {
		return new TpexQuoteClient(restClient(properties.tpexBaseUrl(), properties), objectMapper, clock);
	}

	private RestClient restClient(String baseUrl, QuoteProperties properties) {
		// 一定要設逾時：沒設的話官方網站沒回應時會一直等下去，新增持股就無法在 10 秒內有結果
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
				// 去掉結尾的「/」：避免設定成 http://localhost:9000/ 時，網址變成 //rwd/...
				.baseUrl(baseUrl.replaceAll("/+$", ""))
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.defaultHeader(HttpHeaders.ACCEPT, "application/json")
				.build();
	}
}
