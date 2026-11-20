package io.qiao.quote;

import static io.qiao.quote.TwseQuoteClientTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;

/**
 * 櫃買中心查詢測試（MockRestServiceServer 模擬回應，不連網）。
 */
class TpexQuoteClientTest {

	private static final String BASE_URL = "http://tpex.test";
	private static final String PATH = BASE_URL + "/www/zh-tw/afterTrading/tradingStock";

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T04:00:00Z"), ZoneId.of("Asia/Taipei"));

	private MockRestServiceServer server;
	private TpexQuoteClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TpexQuoteClient(builder.build(), new ObjectMapper(), clock);
	}

	@Test
	void 查到_名稱取最外層name_日期參數是西元年斜線格式() throws IOException {
		server.expect(requestTo(startsWith(PATH)))
				.andExpect(queryParam("code", "6488"))
				.andExpect(queryParam("date", "2026/11/01"))
				.andExpect(queryParam("response", "json"))
				.andRespond(withSuccess(json("tpex-6488-202611.json"), MediaType.APPLICATION_JSON));

		StockQuote quote = client.fetchLatest("6488").orElseThrow();

		assertThat(quote.name()).isEqualTo("環球晶");
		assertThat(quote.market()).isEqualTo(Market.TPEX);
		assertThat(quote.closePrice()).isEqualByComparingTo("490.00");
		assertThat(quote.priceDate()).isEqualTo(LocalDate.of(2026, 11, 19));
		server.verify();
	}

	@Test
	void stat是ok但data是空的_兩個月都空_代表上櫃沒有這檔() throws IOException {
		server.expect(queryParam("date", "2026/11/01"))
				.andRespond(withSuccess(json("tpex-notfound.json"), MediaType.APPLICATION_JSON));
		server.expect(queryParam("date", "2026/10/01"))
				.andRespond(withSuccess(json("tpex-notfound.json"), MediaType.APPLICATION_JSON));

		assertThat(client.fetchLatest("2330")).isEmpty();
		server.verify();
	}

	@Test
	void 連不上_丟出無法取得() {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withException(new ConnectException("Connection refused")));

		assertThatThrownBy(() -> client.fetchLatest("6488"))
				.isInstanceOf(QuoteUnavailableException.class);
	}
}
