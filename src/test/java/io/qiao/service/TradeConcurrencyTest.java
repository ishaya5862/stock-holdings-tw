package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.transaction.support.TransactionTemplate;

import io.qiao.calculation.OversellException;
import io.qiao.calculation.TradeSide;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.exception.HoldingBusyException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.StockRepository;
import io.qiao.repository.TradeRepository;

/**
 * 手機與電腦同時操作同一份持股（題目第 6 題的上線事實；C3-1 悲觀鎖、C3-2 等鎖 3 秒、C3-4 同時首次買進）。
 *
 * 不加 @Transactional：要讓兩個執行緒各自開「真的」交易、真的搶資料庫的鎖（H2 也支援 SELECT … FOR UPDATE）。
 * 所以每個測試前後自己清資料（先刪交易、再刪持股、最後股票：外鍵順序）。
 */
@SpringBootTest
class TradeConcurrencyTest {

	private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingLock holdingLock;

	@Autowired
	private StockService stockService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private StockLookupService stockLookupService;

	private final ExecutorService threads = Executors.newFixedThreadPool(2);

	@BeforeEach
	void setUp() {
		clean();
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> quote(invocation.getArgument(0)));
	}

	@AfterEach
	void tearDown() {
		threads.shutdownNow();
		clean();
	}

	@Test
	void 手機和電腦同時賣出_合起來會賣超_只有一邊成功() throws Exception {
		tradeService.buy("2330", DAY, 1000, new BigDecimal("600.00"));
		CountDownLatch start = new CountDownLatch(1);
		Callable<String> sell600 = () -> {
			start.await();
			try {
				tradeService.sell("2330", DAY, 600, new BigDecimal("650.00"));
				return "成功";
			} catch (OversellException e) {
				return "賣超";
			}
		};

		Future<String> phone = threads.submit(sell600);
		Future<String> pc = threads.submit(sell600);
		start.countDown(); // 兩邊同時送出

		List<String> results = new ArrayList<>(List.of(phone.get(10, TimeUnit.SECONDS), pc.get(10, TimeUnit.SECONDS)));
		// 各自看都「1,000 股夠賣 600」，但有鎖排隊：後到的讀到剩 400 股 → 賣超
		assertThat(results).containsExactlyInAnyOrder("成功", "賣超");
		assertThat(tradeRepository.count()).isEqualTo(2); // 1 買 1 賣
	}

	@Test
	void 等鎖超過3秒_回資料正在更新_不會一直等() throws Exception {
		tradeService.buy("2330", DAY, 1000, new BigDecimal("600.00"));
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		// 另一個裝置：鎖住這檔後卡住（模擬很慢的請求）
		Future<?> slow = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			holdingLock.byCode("2330", IllegalStateException::new);
			locked.countDown();
			await(release);
		}));
		await(locked);

		long startedAt = System.nanoTime();
		assertThatThrownBy(() -> tradeService.sell("2330", DAY, 100, new BigDecimal("650.00")))
				.isInstanceOf(HoldingBusyException.class)
				.hasMessage("資料正在更新，請稍後再試");
		Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

		release.countDown();
		slow.get(10, TimeUnit.SECONDS);
		// 等鎖上限 3 秒（H2：SET LOCK_TIMEOUT 3000）；遠低於 App 的 10 秒重送
		assertThat(waited).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(8));
		assertThat(tradeRepository.count()).isEqualTo(1); // 賣出沒有寫入
	}

	@Test
	void 手機和電腦同時買進還沒持有的同一檔_兩筆都記錄_只有一筆持股() {
		// 模擬：我們向官方查詢的這段時間（交易外），另一個裝置剛好建立了這檔持股並買進
		when(stockLookupService.lookup("2330")).thenAnswer(invocation -> {
			StockQuote quote = quote("2330");
			transactionTemplate.executeWithoutResult(status -> {
				stockService.recordQuote(quote);
				Holding other = new Holding();
				other.setCode("2330");
				other.setCreatedAt(LocalDateTime.now());
				other.setUpdatedAt(LocalDateTime.now());
				Holding saved = holdingRepository.saveAndFlush(other);
				tradeRepository.save(Trade.create(saved.getId(), TradeSide.BUY, DAY, 200, new BigDecimal("590.00"), LocalDateTime.now()));
			});
			return quote;
		});

		TradeResult result = tradeService.buy("2330", DAY, 300, new BigDecimal("600.00"));

		// 建立持股時撞到「代號唯一」→ 改成加到另一個裝置剛建立的持股（C3-4），使用者不會收到錯誤
		assertThat(holdingRepository.count()).isEqualTo(1);
		assertThat(tradeRepository.count()).isEqualTo(2);
		assertThat(result.position().shares()).isEqualTo(500);
	}

	private static StockQuote quote(String code) {
		return new StockQuote(code, "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20));
	}

	private void clean() {
		tradeRepository.deleteAll();
		holdingRepository.deleteAll();
		stockRepository.deleteAll();
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
