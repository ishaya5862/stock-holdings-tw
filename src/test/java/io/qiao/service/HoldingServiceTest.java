package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.StockRepository;

/**
 * 持股 Service 測試（H2 記憶體資料庫，Flyway 一樣會建表）。
 * 每個測試都加 @Transactional，結束後自動復原資料，測試之間互不影響。
 * 查官方行情的 StockLookupService 換成假的（@MockitoBean），測試不會連到證交所、櫃買中心。
 */
@SpringBootTest
@Transactional
class HoldingServiceTest {

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockRepository stockRepository;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		// 預設：任何代號都查得到，名稱「測試股」、上市、收盤價 100
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "測試股", Market.TWSE, new BigDecimal("100.00"), LocalDate.of(2026, 11, 19)));
	}

	@Test
	void 新增持股_官方查到的名稱市場與上次收盤價存進股票表() {
		when(stockLookupService.lookup("6488")).thenReturn(new StockQuote(
				"6488", "環球晶", Market.TPEX, new BigDecimal("490.00"), LocalDate.of(2026, 11, 19)));

		holdingService.create("6488", 1, new BigDecimal("600"));

		Stock stock = stockRepository.findById("6488").orElseThrow();
		assertThat(stock.getName()).isEqualTo("環球晶");
		assertThat(stock.getMarket()).isEqualTo(Market.TPEX);
		assertThat(stock.getLastClosePrice()).isEqualByComparingTo("490.00");
		assertThat(stock.getLastPriceDate()).isEqualTo(LocalDate.of(2026, 11, 19));
	}

	@Test
	void 排程查到另一個市場的行情_股票表市場別更新_轉上市_持股不受影響() {
		when(stockLookupService.lookup("6488")).thenReturn(new StockQuote(
				"6488", "環球晶", Market.TPEX, new BigDecimal("490.00"), LocalDate.of(2026, 11, 19)));
		Holding saved = holdingService.create("6488", 1, new BigDecimal("600"));

		stockService.recordQuote(new StockQuote(
				"6488", "環球晶", Market.TWSE, new BigDecimal("500.00"), LocalDate.of(2026, 11, 20)));

		Stock stock = stockRepository.findById("6488").orElseThrow();
		assertThat(stock.getMarket()).isEqualTo(Market.TWSE);
		assertThat(stock.getLastClosePrice()).isEqualByComparingTo("500.00");
		Holding holding = holdingRepository.findById(saved.getId()).orElseThrow();
		assertThat(holding.getLots()).isEqualTo(1);
		assertThat(holding.getBuyPrice()).isEqualByComparingTo("600");
	}

	@Test
	void 刪除持股_股票資訊保留() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		holdingService.delete(saved.getId());

		assertThat(stockRepository.existsById("2330")).isTrue();
	}

	@Test
	void 刪除後重新新增同一代號_股票表已有資料也能新增() {
		Holding first = holdingService.create("2330", 1, new BigDecimal("1000"));
		holdingService.delete(first.getId());

		Holding again = holdingService.create("2330", 2, new BigDecimal("1100"));

		assertThat(again.getLots()).isEqualTo(2);
	}

	@Test
	void 代號在上市上櫃都查不到_不儲存() {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));

		assertThatThrownBy(() -> holdingService.create("9999", 1, BigDecimal.TEN))
				.isInstanceOf(StockNotFoundException.class);
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 官方連不上_不儲存() {
		when(stockLookupService.lookup("2330")).thenThrow(new QuoteUnavailableException("測試：連不上"));

		assertThatThrownBy(() -> holdingService.create("2330", 1, BigDecimal.TEN))
				.isInstanceOf(QuoteUnavailableException.class);
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 新增持股_代號去空白並轉大寫() {
		Holding saved = holdingService.create(" 00632r ", 2, new BigDecimal("15.50"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCode()).isEqualTo("00632R");
		assertThat(saved.getLots()).isEqualTo(2);
		assertThat(saved.getBuyPrice()).isEqualByComparingTo("15.50");
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	@Test
	void 新增持股_代號開頭的0會保留() {
		Holding saved = holdingService.create("0050", 1, new BigDecimal("150"));

		assertThat(saved.getCode()).isEqualTo("0050");
	}

	@Test
	void 重複新增同一代號_丟出例外且不儲存() {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		assertThatThrownBy(() -> holdingService.create("2330", 2, new BigDecimal("900")))
				.isInstanceOf(DuplicateCodeException.class)
				.hasMessage("2330 已在持股中");
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 重複判斷不受空白和大小寫影響() {
		holdingService.create("00632R", 1, new BigDecimal("15"));

		assertThatThrownBy(() -> holdingService.create(" 00632r", 1, new BigDecimal("15")))
				.isInstanceOf(DuplicateCodeException.class);
	}

	@Test
	void 修改持股_只改張數和買進價() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		Holding updated = holdingService.update(saved.getId(), 3, new BigDecimal("980.50"));

		assertThat(updated.getCode()).isEqualTo("2330");
		assertThat(updated.getLots()).isEqualTo(3);
		assertThat(updated.getBuyPrice()).isEqualByComparingTo("980.50");
	}

	@Test
	void 修改不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.update(99999L, 1, BigDecimal.TEN))
				.isInstanceOf(HoldingNotFoundException.class);
	}

	@Test
	void 刪除持股() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		holdingService.delete(saved.getId());

		assertThat(holdingRepository.existsById(saved.getId())).isFalse();
	}

	@Test
	void 刪除不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.delete(99999L))
				.isInstanceOf(HoldingNotFoundException.class);
	}
}
