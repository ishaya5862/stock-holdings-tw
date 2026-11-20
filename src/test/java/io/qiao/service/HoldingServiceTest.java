package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.TradeSide;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.entity.Trade;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.StockRepository;
import io.qiao.repository.TradeRepository;

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

	@Autowired
	private TradeRepository tradeRepository;

	/** 台北時區的 Clock（TimeConfig）：「今天」以台北為準 */
	@Autowired
	private Clock clock;

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
		HoldingView holding = holdingService.findViewById(saved.getId()); // 2.0：張數、買進價改由交易紀錄算出
		assertThat(holding.lots()).isEqualTo(1L);
		assertThat(holding.buyPrice()).isEqualByComparingTo("600");
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

		assertThat(holdingService.findViewById(again.getId()).lots()).isEqualTo(2L);
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
		assertThat(holdingService.findViewById(saved.getId()).lots()).isEqualTo(2L);
		assertThat(holdingService.findViewById(saved.getId()).buyPrice()).isEqualByComparingTo("15.50");
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
		assertThat(holdingService.findViewById(updated.getId()).lots()).isEqualTo(3L);
		assertThat(holdingService.findViewById(updated.getId()).buyPrice()).isEqualByComparingTo("980.50");
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

	// ===== 2.0 過渡期（第 6 題 C2）：1.0 的新增、修改、刪除同步寫交易紀錄 =====

	@Test
	void 新增持股_同時建立一筆買進紀錄_日期是台北的今天並標記推估() {
		Holding saved = holdingService.create("2330", 2, new BigDecimal("1000.50"));

		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(saved.getId());
		assertThat(trades).hasSize(1);
		Trade trade = trades.get(0);
		assertThat(trade.getSide()).isEqualTo(TradeSide.BUY);
		assertThat(trade.getShares()).isEqualTo(2000L); // 2 張 = 2,000 股
		assertThat(trade.getPrice()).isEqualByComparingTo("1000.50");
		assertThat(trade.getTradeDate()).isEqualTo(LocalDate.now(clock)); // 海外主機也以台北的今天為準
		assertThat(trade.isDateEstimated()).isTrue(); // App 1.0 沒有日期（D3）
	}

	@Test
	void 重複代號新增失敗_不會多出交易紀錄() {
		Holding first = holdingService.create("2330", 1, new BigDecimal("1000"));

		assertThatThrownBy(() -> holdingService.create("2330", 3, new BigDecimal("900")))
				.isInstanceOf(DuplicateCodeException.class);

		assertThat(tradeRepository.findAll()).hasSize(1)
				.allSatisfy(trade -> assertThat(trade.getHoldingId()).isEqualTo(first.getId()));
	}

	@Test
	void 修改持股_同步修改那一筆買進的股數與價格_日期不變() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));
		LocalDate originalDate = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(saved.getId()).get(0).getTradeDate();

		holdingService.update(saved.getId(), 3, new BigDecimal("980.50"));
		tradeRepository.flush();

		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(saved.getId());
		assertThat(trades).hasSize(1);
		assertThat(trades.get(0).getShares()).isEqualTo(3000L);
		assertThat(trades.get(0).getPrice()).isEqualByComparingTo("980.50");
		assertThat(trades.get(0).getTradeDate()).isEqualTo(originalDate);
	}

	@Test
	void 刪除持股_連同交易紀錄一起刪除() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		holdingService.delete(saved.getId());
		holdingRepository.flush(); // 立刻寫入資料庫：外鍵若擋下（先刪持股、後刪交易）會在這裡出錯

		assertThat(holdingRepository.existsById(saved.getId())).isFalse();
		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(saved.getId())).isEmpty();
	}

	@Test
	void 刪除不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.delete(99999L))
				.isInstanceOf(HoldingNotFoundException.class);
	}
}
