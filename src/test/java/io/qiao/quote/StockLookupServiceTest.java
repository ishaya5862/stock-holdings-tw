package io.qiao.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;

/**
 * 判斷上市／上櫃的測試。用假的查詢來源（FakeClient），不連網。
 */
class StockLookupServiceTest {

	private StockLookupService service;

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.close();
		}
	}

	@Test
	void 證交所查到_判斷為上市() {
		service = new StockLookupService(found(Market.TWSE, "台積電"), notFound(Market.TPEX), Duration.ofSeconds(2));

		StockQuote quote = service.lookup("2330");

		assertThat(quote.market()).isEqualTo(Market.TWSE);
		assertThat(quote.name()).isEqualTo("台積電");
	}

	@Test
	void 櫃買中心查到_判斷為上櫃() {
		service = new StockLookupService(notFound(Market.TWSE), found(Market.TPEX, "環球晶"), Duration.ofSeconds(2));

		assertThat(service.lookup("6488").market()).isEqualTo(Market.TPEX);
	}

	@Test
	void 證交所連不上但櫃買中心查到_仍判斷為上櫃() {
		service = new StockLookupService(unavailable(Market.TWSE), found(Market.TPEX, "環球晶"), Duration.ofSeconds(2));

		assertThat(service.lookup("6488").market()).isEqualTo(Market.TPEX);
	}

	@Test
	void 兩邊都確定查無_查無此股票() {
		service = new StockLookupService(notFound(Market.TWSE), notFound(Market.TPEX), Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.lookup("9999"))
				.isInstanceOf(StockNotFoundException.class)
				.hasMessageContaining("9999");
	}

	@Test
	void 一邊查無一邊連不上_不能說查無_回無法取得() {
		service = new StockLookupService(unavailable(Market.TWSE), notFound(Market.TPEX), Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.lookup("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 兩邊都連不上_回無法取得() {
		service = new StockLookupService(unavailable(Market.TWSE), unavailable(Market.TPEX), Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.lookup("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 來源太慢_超過整體時間就放棄_不會一直等() {
		// 兩邊都要 5 秒才回應，整體上限 0.5 秒
		service = new StockLookupService(slow(Market.TWSE), slow(Market.TPEX), Duration.ofMillis(500));

		long start = System.currentTimeMillis();
		assertThatThrownBy(() -> service.lookup("2330"))
				.isInstanceOf(QuoteUnavailableException.class);
		long elapsed = System.currentTimeMillis() - start;

		// 兩邊是「同時」查，總時間約等於整體上限，而不是兩邊相加
		assertThat(elapsed).isLessThan(1500);
	}

	// ---- 假的查詢來源 ----

	private static StockQuoteClient found(Market market, String name) {
		return new FakeClient(market, code -> Optional.of(
				new StockQuote(code, name, market, new BigDecimal("100.00"), LocalDate.of(2026, 11, 19))));
	}

	private static StockQuoteClient notFound(Market market) {
		return new FakeClient(market, code -> Optional.empty());
	}

	private static StockQuoteClient unavailable(Market market) {
		return new FakeClient(market, code -> {
			throw new QuoteUnavailableException(market + " 連不上");
		});
	}

	private static StockQuoteClient slow(Market market) {
		return new FakeClient(market, code -> {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return Optional.empty();
		});
	}

	private record FakeClient(Market market, java.util.function.Function<String, Optional<StockQuote>> behavior)
			implements StockQuoteClient {

		@Override
		public Optional<StockQuote> fetchLatest(String code) {
			return behavior.apply(code);
		}
	}
}
