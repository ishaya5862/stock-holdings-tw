package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.StockRepository;
import io.qiao.repository.TradeRepository;

/**
 * 兩個「獨立交易」交錯執行時，不能互相覆寫對方的欄位（朋友回報的問題）。
 *
 * 情境：背景排程寫回行情（StockService.recordQuote）與使用者修改持股（HoldingService.update）同時發生。
 * 修正方式：行情搬到 stocks 表（V3、V4），背景只寫 stocks、使用者只寫 holdings，兩邊不會寫到同一列。
 * 用兩條執行緒各自開交易，CountDownLatch（倒數門閂：一條執行緒等另一條發出訊號才繼續）控制先後順序。
 *
 * 這個類別刻意「不加」@Transactional：兩個交易都要真的提交（commit）到資料庫，才能重現互相覆寫。
 */
@SpringBootTest
class HoldingConcurrentUpdateTest {

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private StockLookupService stockLookupService;

	private final ExecutorService otherThread = Executors.newSingleThreadExecutor();

	private Long holdingId;

	@BeforeEach
	void setUp() {
		tradeRepository.deleteAll(); // 2.0：交易紀錄的外鍵指向持股、不連帶刪除（C2-2）→ 先刪交易
		holdingRepository.deleteAll();
		stockRepository.deleteAll(); // 先刪持股再刪股票（外鍵）
		when(stockLookupService.lookup(anyString())).thenReturn(new StockQuote(
				"2330", "台積電", Market.TWSE, new BigDecimal("1000.00"), LocalDate.of(2026, 11, 19)));
		// 初始：1 張、買進價 1000、上次收盤價 1000（11/19）
		holdingId = holdingService.create("2330", 1, new BigDecimal("1000")).getId();
	}

	@AfterEach
	void tearDown() {
		otherThread.shutdownNow();
		tradeRepository.deleteAll(); // 2.0：交易紀錄的外鍵指向持股、不連帶刪除（C2-2）→ 先刪交易
		holdingRepository.deleteAll();
		stockRepository.deleteAll(); // 先刪持股再刪股票（外鍵）
	}

	@Test
	void 背景行情先讀_使用者修改先提交_背景再提交_使用者的修改不能被蓋回去() throws Exception {
		CountDownLatch backgroundHasRead = new CountDownLatch(1);
		CountDownLatch userCommitted = new CountDownLatch(1);
		StockQuote newQuote = new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 20));

		// 背景交易：先讀到「1 張、1000」→ 等使用者提交 → 寫回行情 → 提交
		Future<?> background = otherThread.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			holdingRepository.findById(holdingId).orElseThrow(); // 讀到舊資料（1 張、1000）
			backgroundHasRead.countDown();
			await(userCommitted);
			stockService.recordQuote(newQuote);
		}));

		// 使用者交易：在背景讀完之後，修改成「5 張、980.50」並提交
		await(backgroundHasRead);
		holdingService.update(holdingId, 5, new BigDecimal("980.50"));
		userCommitted.countDown();
		background.get(10, TimeUnit.SECONDS);

		Holding result = holdingRepository.findById(holdingId).orElseThrow();
		assertThat(result.getLots()).as("使用者改的張數").isEqualTo(5);
		assertThat(result.getBuyPrice()).as("使用者改的買進價").isEqualByComparingTo("980.50");
		assertThat(stockRepository.findById("2330").orElseThrow().getLastClosePrice()).as("背景寫的收盤價").isEqualByComparingTo("1100.00");
	}

	@Test
	void 使用者修改先讀_背景行情先提交_使用者再提交_背景寫的收盤價不能被蓋回去() throws Exception {
		CountDownLatch userHasRead = new CountDownLatch(1);
		CountDownLatch backgroundCommitted = new CountDownLatch(1);
		StockQuote newQuote = new StockQuote("2330", "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 20));

		// 使用者交易：先讀到舊資料（上次收盤價 1000）→ 等背景提交 → 修改張數 → 提交
		Future<?> user = otherThread.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			holdingRepository.findById(holdingId).orElseThrow();
			userHasRead.countDown();
			await(backgroundCommitted);
			holdingService.update(holdingId, 5, new BigDecimal("980.50"));
		}));

		// 背景交易：在使用者讀完之後寫回新的收盤價 1100 並提交
		await(userHasRead);
		stockService.recordQuote(newQuote);
		backgroundCommitted.countDown();
		user.get(10, TimeUnit.SECONDS);

		Holding result = holdingRepository.findById(holdingId).orElseThrow();
		assertThat(result.getLots()).as("使用者改的張數").isEqualTo(5);
		assertThat(stockRepository.findById("2330").orElseThrow().getLastClosePrice()).as("背景寫的收盤價").isEqualByComparingTo("1100.00");
		assertThat(stockRepository.findById("2330").orElseThrow().getLastPriceDate()).as("背景寫的收盤日期").isEqualTo(LocalDate.of(2026, 11, 20));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("等待另一個交易逾時");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
