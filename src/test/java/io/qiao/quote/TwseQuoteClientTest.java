package io.qiao.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;

/**
 * 證交所查詢測試。
 * 用 MockRestServiceServer 模擬證交所的回應，不會真的連到證交所（題目要求測試不可連官方）。
 * 測試資料（src/test/resources/quote）取自實際回應的格式，價格為測試用。
 */
class TwseQuoteClientTest {

	private static final String BASE_URL = "http://twse.test";
	private static final String PATH = BASE_URL + "/rwd/zh/afterTrading/STOCK_DAY";

	/** 固定在台北時間 2026-11-20 12:00（UTC 04:00），本月 = 11 月、上個月 = 10 月 */
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T04:00:00Z"), ZoneId.of("Asia/Taipei"));

	private MockRestServiceServer server;
	private TwseQuoteClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TwseQuoteClient(builder.build(), new ObjectMapper(), clock);
	}

	@Test
	void 查到_取本月最後一個交易日的收盤價與名稱() throws IOException {
		server.expect(requestTo(startsWith(PATH)))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("date", "20261101"))
				.andExpect(queryParam("stockNo", "2330"))
				.andExpect(queryParam("response", "json"))
				.andRespond(withSuccess(json("twse-2330-202611.json"), MediaType.APPLICATION_JSON));

		Optional<StockQuote> quote = client.fetchLatest("2330");

		assertThat(quote).isPresent();
		assertThat(quote.get().name()).isEqualTo("台積電");
		assertThat(quote.get().market()).isEqualTo(Market.TWSE);
		assertThat(quote.get().closePrice()).isEqualByComparingTo("2315.00");
		assertThat(quote.get().priceDate()).isEqualTo(LocalDate.of(2026, 11, 19));
		server.verify();
	}

	@Test
	void 最後一天沒有成交_往前取有收盤價的那天() throws IOException {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withSuccess(json("twse-0050-dash.json"), MediaType.APPLICATION_JSON));

		StockQuote quote = client.fetchLatest("0050").orElseThrow();

		assertThat(quote.priceDate()).isEqualTo(LocalDate.of(2026, 11, 18));
		assertThat(quote.closePrice()).isEqualByComparingTo("94.10");
	}

	@Test
	void 本月沒有資料_改查上個月() throws IOException {
		// 情境：每月第一個交易日收盤前，本月查無資料，但股票是存在的
		server.expect(queryParam("date", "20261101"))
				.andRespond(withSuccess(json("twse-notfound.json"), MediaType.APPLICATION_JSON));
		server.expect(queryParam("date", "20261001"))
				.andRespond(withSuccess(json("twse-2330-202610.json"), MediaType.APPLICATION_JSON));

		StockQuote quote = client.fetchLatest("2330").orElseThrow();

		assertThat(quote.priceDate()).isEqualTo(LocalDate.of(2026, 10, 30));
		assertThat(quote.closePrice()).isEqualByComparingTo("2370.00");
		server.verify();
	}

	@Test
	void 本月和上個月都沒有_代表上市沒有這檔() throws IOException {
		server.expect(queryParam("date", "20261101"))
				.andRespond(withSuccess(json("twse-notfound.json"), MediaType.APPLICATION_JSON));
		server.expect(queryParam("date", "20261001"))
				.andRespond(withSuccess(json("twse-notfound.json"), MediaType.APPLICATION_JSON));

		assertThat(client.fetchLatest("6488")).isEmpty();
		server.verify();
	}

	@Test
	void 逾時_丟出無法取得而不是查無() {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.fetchLatest("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 伺服器錯誤_丟出無法取得() {
		server.expect(requestTo(startsWith(PATH))).andRespond(withServerError());

		assertThatThrownBy(() -> client.fetchLatest("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 回應不是JSON_例如被擋時的HTML頁面_丟出無法取得() {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withSuccess("<html>請稍後再試</html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> client.fetchLatest("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 其他stat訊息_不能當成查無_丟出無法取得() {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withSuccess("{\"stat\":\"查詢過於頻繁，請稍後再試\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetchLatest("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 回應缺少收盤價欄位_丟出無法取得() {
		server.expect(requestTo(startsWith(PATH)))
				.andRespond(withSuccess("{\"stat\":\"OK\",\"title\":\"\",\"fields\":[\"日期\"],\"data\":[[\"115/11/19\"]]}",
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetchLatest("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	static String json(String fileName) throws IOException {
		return new ClassPathResource("quote/" + fileName).getContentAsString(StandardCharsets.UTF_8);
	}
}
