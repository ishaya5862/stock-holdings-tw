package io.qiao.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;

/**
 * 判斷上市／上櫃的測試。用假的查詢來源（FakeQuoteClient），不連網。
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

	// ---- 第 6 題 L2 b：查「交易日期那個月」（補登已下市股票） ----

	private static final YearMonth MARCH_2017 = YearMonth.of(2017, 3);

	private static StockQuote march2017(String code, Market market) {
		return new StockQuote(code, "日月光", market, new BigDecimal("38.50"), LocalDate.of(2017, 3, 31));
	}

	@Test
	void 某個月_證交所有成交_回傳那個月最後一天的行情() {
		FakeQuoteClient twse = new FakeQuoteClient(Market.TWSE, code -> Optional.empty())
				.withMonthBehavior((code, month) -> month.equals(MARCH_2017) ? Optional.of(march2017(code, Market.TWSE)) : Optional.empty());
		service = new StockLookupService(twse, notFound(Market.TPEX), Duration.ofSeconds(2));

		Optional<StockQuote> quote = service.findInMonth("2311", MARCH_2017);

		assertThat(quote).get().extracting(StockQuote::name, StockQuote::market, StockQuote::priceDate)
				.containsExactly("日月光", Market.TWSE, LocalDate.of(2017, 3, 31));
		assertThat(twse.requestedMonths()).containsExactly(MARCH_2017);
	}

	@Test
	void 某個月_兩邊都確定沒有成交_回傳空的() {
		service = new StockLookupService(notFound(Market.TWSE), notFound(Market.TPEX), Duration.ofSeconds(2));
		assertThat(service.findInMonth("9999", MARCH_2017)).isEmpty();
	}

	@Test
	void 某個月_一邊連不上又沒查到_不下結論() {
		FakeQuoteClient twse = new FakeQuoteClient(Market.TWSE, code -> Optional.empty())
				.withMonthBehavior((code, month) -> { throw new QuoteUnavailableException("TWSE 連不上"); });
		service = new StockLookupService(twse, notFound(Market.TPEX), Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.findInMonth("2311", MARCH_2017)).isInstanceOf(QuoteUnavailableException.class);
	}

	@Test
	void 某個月早於證交所資料範圍_不查證交所_只看櫃買中心() {
		FakeQuoteClient twse = new FakeQuoteClient(Market.TWSE, code -> Optional.empty())
				.withEarliestMonth(YearMonth.of(2010, 1));
		FakeQuoteClient tpex = new FakeQuoteClient(Market.TPEX, code -> Optional.empty())
				.withMonthBehavior((code, month) -> Optional.of(
						new StockQuote(code, "世界", Market.TPEX, new BigDecimal("20.00"), LocalDate.of(2005, 3, 31))));
		service = new StockLookupService(twse, tpex, Duration.ofSeconds(2));

		assertThat(service.findInMonth("5347", YearMonth.of(2005, 3))).get()
				.extracting(StockQuote::market).isEqualTo(Market.TPEX);
		assertThat(twse.requestedMonths()).isEmpty(); // 2010 以前證交所會回「查詢日期小於99年1月4日」，不送出

		// 櫃買中心也沒有 → 回傳空的（呼叫端的訊息會說明證交所只有 2010 年起的資料）
		FakeQuoteClient emptyTpex = new FakeQuoteClient(Market.TPEX, code -> Optional.empty());
		service = new StockLookupService(twse, emptyTpex, Duration.ofSeconds(2));
		assertThat(service.findInMonth("2311", YearMonth.of(2005, 3))).isEmpty();
	}

	// ---- 假的查詢來源 ----

	private static StockQuoteClient found(Market market, String name) {
		return new FakeQuoteClient(market, code -> Optional.of(
				new StockQuote(code, name, market, new BigDecimal("100.00"), LocalDate.of(2026, 11, 19))));
	}

	private static StockQuoteClient notFound(Market market) {
		return new FakeQuoteClient(market, code -> Optional.empty());
	}

	private static StockQuoteClient unavailable(Market market) {
		return new FakeQuoteClient(market, code -> {
			throw new QuoteUnavailableException(market + " 連不上");
		});
	}

	private static StockQuoteClient slow(Market market) {
		return new FakeQuoteClient(market, code -> {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return Optional.empty();
		});
	}
}
