package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;

/**
 * 持股版本號（C3-9）：這檔有任何交易異動，版本號一定 +1（D-47）。
 *
 * 原本的問題：版本號靠「updatedAt 改變」讓 Hibernate 判斷有異動；兩次異動拿到同一個時間值時，
 * Hibernate 認為沒變 → 版本號沒有 +1。TradeServiceTest 的版本號測試因此偶爾失敗（連續兩次買進剛好在同一個時鐘刻度）。
 *
 * 這裡用「固定時鐘」：每次操作拿到的時間都一樣，原本的問題每次都會重現（不再是偶發），修正後一定通過。
 */
@SpringBootTest
@Transactional
class HoldingVersionTest {

	/** 固定在 2026-11-20 10:00（台北）：所有操作的「現在」都一樣 */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary // 有兩個 Clock 時優先用這個（正式的 TimeConfig.clock 照樣存在）
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneId.of("Asia/Taipei"));
		}
	}

	private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
	private static final LocalDate D2 = LocalDate.of(2026, 5, 15);
	private static final LocalDate D3 = LocalDate.of(2026, 8, 20);

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	@Test
	void 同一個時間連續買進賣出_每次異動版本號都加一_新建立的持股是0() {
		Holding holding = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).holding();
		assertThat(versionOf(holding)).isZero(); // 建立持股＋第一筆買進：版本 0（和原本一樣）

		tradeService.buy("2330", D2, 500, new BigDecimal("620.00"));
		assertThat(versionOf(holding)).isEqualTo(1);

		tradeService.sell("2330", D3, 200, new BigDecimal("700.00"));
		assertThat(versionOf(holding)).isEqualTo(2);
	}

	@Test
	void 同一個時間修改與刪除交易_版本號都加一() {
		Holding holding = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).holding();
		Trade second = tradeService.buy("2330", D2, 500, new BigDecimal("620.00")).trade(); // 版本 1

		tradeService.updateTrade(second.getId(), second.getVersion(), D2, 400, new BigDecimal("620.00"));
		assertThat(versionOf(holding)).isEqualTo(2);

		Trade updated = tradeService.buy("2330", D3, 100, new BigDecimal("630.00")).trade(); // 版本 3
		tradeService.deleteTrade(updated.getId(), updated.getVersion());
		assertThat(versionOf(holding)).isEqualTo(4);
	}

	@Test
	void 同一個時間App1點0修改_版本號也加一() {
		Holding holding = holdingService.create("2330", 1, new BigDecimal("600")); // App 1.0 新增：版本 0
		assertThat(versionOf(holding)).isZero();

		holdingService.update(holding.getId(), 2, new BigDecimal("610"));
		assertThat(versionOf(holding)).isEqualTo(1);
	}

	/** 先把還沒寫進資料庫的異動寫進去（版本號在寫入時才 +1），再讀版本號 */
	private long versionOf(Holding holding) {
		holdingRepository.flush();
		return holdingRepository.findById(holding.getId()).orElseThrow().getVersion();
	}
}
