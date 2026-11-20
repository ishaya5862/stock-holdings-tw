package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.OversellException;
import io.qiao.calculation.TradeSide;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.exception.InvalidTradeException;
import io.qiao.exception.NotHeldException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.exception.TradeVersionConflictException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;

/**
 * 2.0 交易服務測試（第 6 題 C3）：買進、賣出、修改、刪除，以及輸入規則與日期提醒。
 * 手機與電腦同時操作（鎖）另外在 TradeConcurrencyTest（需要真的兩條連線，不能包在同一個測試交易裡）。
 * 預期值都是手算（算式寫在註解）。
 */
@SpringBootTest
@Transactional
class TradeServiceTest {

	private static final LocalDate D1 = LocalDate.of(2026, 3, 2);  // 星期一
	private static final LocalDate D2 = LocalDate.of(2026, 5, 15); // 星期五
	private static final LocalDate D3 = LocalDate.of(2026, 8, 20); // 星期四

	@Autowired
	private TradeService tradeService;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	// ===== 買進 =====

	@Test
	void 買進還沒持有的代號_查官方建立持股與交易_回傳重算結果() {
		TradeResult result = tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));

		// 180,000 + 手續費 256（256.5 捨去）= 180,256
		assertThat(result.holding().getCode()).isEqualTo("2330");
		assertThat(result.trade().getSide()).isEqualTo(TradeSide.BUY);
		assertThat(result.trade().getShares()).isEqualTo(300L);
		assertThat(result.trade().isDateEstimated()).isFalse(); // 2.0 的日期是使用者填的
		assertThat(result.position().shares()).isEqualTo(300);
		assertThat(result.position().remainingCost()).isEqualByComparingTo("180256");
		assertThat(result.position().unrealized()).as("剛查到的收盤價已放進快取").isNotNull();
	}

	// ===== 第 6 題 L2 b：補登已下市股票的舊交易（近兩個月查不到 → 查交易日期那個月） =====

	@Test
	void 已下市的股票_交易日期那個月有成交_可以記錄_那個月的價格不當成現在的收盤價() {
		when(stockLookupService.lookup("2311")).thenThrow(new StockNotFoundException("2311"));
		when(stockLookupService.findInMonth("2311", YearMonth.of(2017, 3))).thenReturn(Optional.of(
				new StockQuote("2311", "日月光", Market.TWSE, new BigDecimal("38.50"), LocalDate.of(2017, 3, 31))));

		TradeResult result = tradeService.buy("2311", LocalDate.of(2017, 3, 15), 1000, new BigDecimal("37.00"));

		assertThat(result.holding().getCode()).isEqualTo("2311");
		assertThat(result.position().shares()).isEqualTo(1000);
		// 2017 年的收盤價不是現在的價格：不放進行情快取 → 不算未實現損益（之後由背景排程判斷「近期無成交」）
		assertThat(result.position().unrealized()).isNull();
	}

	@Test
	void 交易日期在近兩個月內_查不到就是查不到_不多查() {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));
		LocalDate lastMonth = LocalDate.now(clock).minusMonths(1).withDayOfMonth(1);

		assertThatThrownBy(() -> tradeService.buy("9999", lastMonth, 100, new BigDecimal("10.00")))
				.isInstanceOf(StockNotFoundException.class)
				.hasMessageContaining("近兩個月都沒有成交資料");
		verify(stockLookupService, never()).findInMonth(anyString(), any());
	}

	@Test
	void 交易日期那個月也查不到_訊息寫出查過的月份_不寫入() {
		when(stockLookupService.lookup("9999")).thenThrow(new StockNotFoundException("9999"));
		// findInMonth 沒設定 → Mockito 對 Optional 預設回傳空的（= 那個月也查不到）

		assertThatThrownBy(() -> tradeService.buy("9999", LocalDate.of(2017, 3, 15), 100, new BigDecimal("10.00")))
				.isInstanceOf(StockNotFoundException.class)
				.hasMessageContaining("近兩個月與 2017 年 3 月都沒有成交資料")
				.hasMessageContaining("只支援上市、上櫃的股票")
				.hasMessageNotContaining("2010");
		assertThat(holdingRepository.count()).isZero();
	}

	@Test
	void 二零一零年以前_證交所沒有資料_訊息說明() {
		when(stockLookupService.lookup("2311")).thenThrow(new StockNotFoundException("2311"));

		assertThatThrownBy(() -> tradeService.buy("2311", LocalDate.of(2005, 3, 15), 100, new BigDecimal("10.00")))
				.isInstanceOf(StockNotFoundException.class)
				.hasMessageContaining("證交所只提供 2010 年起的資料");
	}

	@Test
	void 已持有的代號再買_不再查官方() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));
		tradeService.buy("2330", D2, 500, new BigDecimal("620.00"));

		verify(stockLookupService, times(1)).lookup("2330");
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 代號全形小寫統一寫法_視為同一檔() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));
		TradeResult result = tradeService.buy(" ２３３０ ", D2, 100, new BigDecimal("610.00"));

		assertThat(result.holding().getCode()).isEqualTo("2330");
		assertThat(result.position().shares()).isEqualTo(400);
	}

	// ===== 賣出 =====

	@Test
	void 賣出一部分_算出已實現損益_平均成本不變() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));
		tradeService.buy("2330", D2, 500, new BigDecimal("620.00"));

		TradeResult result = tradeService.sell("2330", D3, 200, new BigDecimal("700.00"));

		// C1 的台積電範例：已實現 16,706.75、剩 600 股、剩餘成本 368,022.75、平均成本 613.37
		assertThat(result.trade().getSide()).isEqualTo(TradeSide.SELL);
		assertThat(result.position().realizedPnl()).isEqualByComparingTo("16706.75");
		assertThat(result.position().shares()).isEqualTo(600);
		assertThat(result.position().remainingCost()).isEqualByComparingTo("368022.75");
		assertThat(result.position().averageCost()).isEqualByComparingTo("613.37");
	}

	@Test
	void 賣超_擋下而且不寫入() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));

		assertThatThrownBy(() -> tradeService.sell("2330", D2, 301, new BigDecimal("700.00")))
				.isInstanceOf(OversellException.class)
				.hasMessageContaining("只持有 300 股");
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	@Test
	void 補登過去日期的賣出_當時股數不夠_擋下() {
		// 1 月買 100、6 月買 100；補登 3 月賣 150：現在總數 200 夠，但 3 月當時只有 100（C3-1 討論的例子）
		tradeService.buy("2330", LocalDate.of(2026, 1, 5), 100, new BigDecimal("600.00"));
		tradeService.buy("2330", LocalDate.of(2026, 6, 1), 100, new BigDecimal("600.00"));

		assertThatThrownBy(() -> tradeService.sell("2330", LocalDate.of(2026, 3, 2), 150, new BigDecimal("650.00")))
				.isInstanceOf(OversellException.class)
				.hasMessageContaining("2026-03-02")
				.hasMessageContaining("只持有 100 股");
	}

	@Test
	void 賣出沒有持有過的代號_找不到() {
		assertThatThrownBy(() -> tradeService.sell("2330", D1, 100, new BigDecimal("600.00")))
				.isInstanceOf(NotHeldException.class)
				.hasMessage("目前沒有持有 2330");
	}

	@Test
	void 全部賣光_持股列保留_之後再買沿用同一列() {
		Long holdingId = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).holding().getId();

		TradeResult soldOut = tradeService.sell("2330", D2, 300, new BigDecimal("650.00"));
		assertThat(soldOut.position().shares()).isZero();
		assertThat(soldOut.holdingDeleted()).isFalse();

		TradeResult rebought = tradeService.buy("2330", D3, 100, new BigDecimal("700.00"));
		assertThat(rebought.holding().getId()).isEqualTo(holdingId);
		assertThat(rebought.position().shares()).isEqualTo(100);
	}

	// ===== 修改一筆交易 =====

	@Test
	void 修改交易_日期股數價格_改了日期就不再是推估_版本號加一() {
		// 1.0 新增的持股：日期是推估的（D3）
		Long holdingId = holdingService.create("2330", 1, new BigDecimal("600")).getId();
		Trade trade = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holdingId).get(0);
		assertThat(trade.isDateEstimated()).isTrue();
		Long version = trade.getVersion();

		TradeResult result = tradeService.updateTrade(trade.getId(), version, D1, 1500, new BigDecimal("590.50"));

		assertThat(result.trade().getTradeDate()).isEqualTo(D1);
		assertThat(result.trade().isDateEstimated()).isFalse();
		assertThat(result.trade().getShares()).isEqualTo(1500L);
		assertThat(result.trade().getPrice()).isEqualByComparingTo("590.50");
		assertThat(result.trade().getVersion()).isEqualTo(version + 1);
		assertThat(result.position().shares()).isEqualTo(1500);
	}

	@Test
	void 修改交易_版本號不符_代表另一個裝置改過_擋下() {
		Trade trade = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).trade();

		assertThatThrownBy(() -> tradeService.updateTrade(trade.getId(), trade.getVersion() + 1, D1, 100, new BigDecimal("600")))
				.isInstanceOf(TradeVersionConflictException.class);
		assertThatThrownBy(() -> tradeService.updateTrade(trade.getId(), null, D1, 100, new BigDecimal("600")))
				.isInstanceOf(TradeVersionConflictException.class);
	}

	@Test
	void 修改舊的買進造成之後賣超_擋下_資料不變() {
		Trade buy = tradeService.buy("2330", D1, 500, new BigDecimal("600.00")).trade();
		tradeService.sell("2330", D2, 300, new BigDecimal("650.00"));

		assertThatThrownBy(() -> tradeService.updateTrade(buy.getId(), buy.getVersion(), D1, 100, new BigDecimal("600.00")))
				.isInstanceOf(OversellException.class)
				.hasMessageContaining("只持有 100 股");
		assertThat(tradeRepository.findById(buy.getId()).orElseThrow().getShares()).isEqualTo(500L);
	}

	@Test
	void 修改不存在的交易_找不到() {
		assertThatThrownBy(() -> tradeService.updateTrade(99999L, 0L, D1, 100, new BigDecimal("600")))
				.isInstanceOf(TradeNotFoundException.class);
	}

	// ===== 刪除一筆交易 =====

	@Test
	void 刪除一筆交易_重算() {
		tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));
		Trade second = tradeService.buy("2330", D2, 500, new BigDecimal("620.00")).trade();

		TradeResult result = tradeService.deleteTrade(second.getId(), second.getVersion());

		assertThat(result.holdingDeleted()).isFalse();
		assertThat(result.position().shares()).isEqualTo(300);
	}

	@Test
	void 刪除最後一筆交易_持股列也一起刪除() {
		TradeResult bought = tradeService.buy("2330", D1, 300, new BigDecimal("600.00"));

		TradeResult result = tradeService.deleteTrade(bought.trade().getId(), bought.trade().getVersion());

		assertThat(result.holdingDeleted()).isTrue();
		assertThat(holdingRepository.existsById(bought.holding().getId())).isFalse();
		assertThat(tradeRepository.count()).isZero();
	}

	@Test
	void 刪除買進造成之後賣超_擋下_什麼都不刪() {
		Trade buy = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).trade();
		tradeService.sell("2330", D2, 100, new BigDecimal("650.00"));

		assertThatThrownBy(() -> tradeService.deleteTrade(buy.getId(), buy.getVersion()))
				.isInstanceOf(OversellException.class);
		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	@Test
	void 刪除時版本號不符_擋下() {
		Trade trade = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).trade();

		assertThatThrownBy(() -> tradeService.deleteTrade(trade.getId(), trade.getVersion() + 1))
				.isInstanceOf(TradeVersionConflictException.class);
		assertThat(tradeRepository.count()).isEqualTo(1);
	}

	// ===== 持股版本號（C3-9） =====

	@Test
	void 持股版本號_這檔有任何交易異動就加一() {
		Holding holding = tradeService.buy("2330", D1, 300, new BigDecimal("600.00")).holding();
		long before = holdingRepository.findById(holding.getId()).orElseThrow().getVersion();

		tradeService.buy("2330", D2, 100, new BigDecimal("610.00"));
		holdingRepository.flush();

		assertThat(holdingRepository.findById(holding.getId()).orElseThrow().getVersion()).isGreaterThan(before);
	}

	// ===== 輸入規則（硬性，不合格不儲存） =====

	@Test
	void 股數規則_最少1股_最多99999999股超過時說明分批() {
		assertInvalid(() -> tradeService.buy("2330", D1, 0, new BigDecimal("600")), "shares", "股數最少 1 股");
		assertInvalid(() -> tradeService.buy("2330", D1, 100_000_000L, new BigDecimal("600")), "shares",
				"單筆最多 99,999,999 股；數量更大請分成多筆記錄");
		assertThat(tradeService.buy("2330", D1, 99_999_999L, new BigDecimal("1.00")).position().shares())
				.isEqualTo(99_999_999L);
	}

	@Test
	void 價格規則_大於0_最多兩位小數_最大99999999點99() {
		assertInvalid(() -> tradeService.buy("2330", D1, 100, new BigDecimal("0")), "price", "價格必須大於 0");
		assertInvalid(() -> tradeService.buy("2330", D1, 100, new BigDecimal("10.123")), "price", "價格最多兩位小數");
		assertInvalid(() -> tradeService.buy("2330", D1, 100, new BigDecimal("100000000")), "price", "價格最大 99,999,999.99");
		assertThat(tradeService.buy("2330", D1, 1, new BigDecimal("10.10")).trade().getPrice()).isEqualByComparingTo("10.1");
	}

	@Test
	void 金額規則_單筆最多100億元_防多打0() {
		// 朋友回饋 3：股數、價格各自都在上限內，但相乘超過 100 億 → 擋下（App 1.0 的金額才不會超出 JSON 精確範圍）
		assertInvalid(() -> tradeService.buy("2330", D1, 10_000_001L, new BigDecimal("1000.00")), "shares",
				"單筆成交金額（股數 × 價格）最多 100 億元，請確認股數與價格是否多打了 0");
		assertInvalid(() -> tradeService.buy("2330", D1, 99_999_999L, new BigDecimal("99999999.99")), "shares",
				"單筆成交金額（股數 × 價格）最多 100 億元，請確認股數與價格是否多打了 0");
		// 剛好 100 億可以
		assertThat(tradeService.buy("2330", D1, 10_000_000L, new BigDecimal("1000.00")).position().shares())
				.isEqualTo(10_000_000L);
	}

	@Test
	void 金額上限也適用於修改_不能把舊交易改成超過100億() {
		Trade trade = tradeService.buy("2330", D1, 1000, new BigDecimal("600.00")).trade();
		assertInvalid(() -> tradeService.updateTrade(trade.getId(), trade.getVersion(), D1, 20_000_000L,
				new BigDecimal("600.00")), "shares", "單筆成交金額（股數 × 價格）最多 100 億元，請確認股數與價格是否多打了 0");
	}

	@Test
	void App1點0新增照1點0規格_不受100億上限影響() {
		// 1.0 規格允許 999 張 × 99,999,999.99 元（約 99 兆），v1 的新增不經過 2.0 的金額上限
		assertThat(holdingService.create("2330", 999, new BigDecimal("99999999.99")).getId()).isNotNull();
	}

	@Test
	void 日期規則_1990年起_不能晚於台北時間的今天() {
		LocalDate today = LocalDate.now(clock); // TimeConfig 的 Clock 是台北時區：主機在海外也一樣
		assertInvalid(() -> tradeService.buy("2330", today.plusDays(1), 100, new BigDecimal("600")), "trade_date",
				"交易日期不能晚於今天（台北時間 " + today + "）");
		assertInvalid(() -> tradeService.buy("2330", LocalDate.of(1989, 12, 31), 100, new BigDecimal("600")), "trade_date",
				"交易日期不能早於 1990-01-01");
		assertThat(tradeService.buy("2330", LocalDate.of(1990, 1, 1), 100, new BigDecimal("600")).trade().getTradeDate())
				.isEqualTo(LocalDate.of(1990, 1, 1));
		assertThat(tradeService.buy("2330", today, 100, new BigDecimal("600")).trade().getTradeDate()).isEqualTo(today);
	}

	@Test
	void 週末或休市日_伺服器照樣儲存_提醒由前端負責() {
		// 題目 1：a 只擋未來與 1990 年以前；d 「這天可能不是交易日，確定嗎？」在前端提醒，使用者可以照樣送出
		tradeService.buy("2330", LocalDate.of(2026, 11, 14), 100, new BigDecimal("600")); // 星期六
		tradeService.buy("2330", LocalDate.of(2026, 1, 1), 100, new BigDecimal("600"));   // 開國紀念日（休市）

		assertThat(tradeRepository.count()).isEqualTo(2);
	}

	private static void assertInvalid(Runnable action, String field, String message) {
		assertThatThrownBy(action::run)
				.isInstanceOf(InvalidTradeException.class)
				.hasMessage(message)
				.satisfies(e -> assertThat(((InvalidTradeException) e).field()).isEqualTo(field));
	}
}
