package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.TradeSide;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;

/**
 * 資料異常主動檢查（優化 #2，D-32）。
 *
 * 需求：
 * - 有異常 → 寫「一筆」ERROR，開頭是 DATA_INTEGRITY，列出總檔數、異常檔數、每一檔的代號、持股 id、原因
 * - 沒有異常 → 寫一筆 INFO，不寫 ERROR
 * - 檢查本身失敗（例如資料庫連不上）→ 寫 ERROR，但不往外丟例外（啟動時丟出會讓整個程式啟動失敗）
 *
 * 異常資料的做法和 DataErrorIsolationTest 相同：用 TradeRepository 直接寫入（模擬直接改資料庫）。
 */
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class DataIntegrityCheckerTest {

	@Autowired
	private DataIntegrityChecker checker;

	@Autowired
	private TradeService tradeService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "測試股", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
		// 正常的持股：2330 買 300 股
		tradeService.buy("2330", LocalDate.of(2026, 3, 2), 300, new BigDecimal("600.00"));
	}

	@Test
	void 有異常_寫一筆ERROR_列出檔數代號id與原因(CapturedOutput output) {
		Long brokenId = addBrokenHolding("2363");

		List<String> problems = checker.check("測試");

		assertThat(problems).containsExactly("2363（持股 id " + brokenId + "）：2026-03-02 賣出 1000 股，但當時只持有 0 股");
		List<String> lines = tagLines(output);
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0))
				.contains("ERROR")
				.contains("DATA_INTEGRITY 資料檢查（測試）：共 2 檔持股，其中 1 檔交易資料異常")
				.contains("2363（持股 id " + brokenId + "）：2026-03-02 賣出 1000 股，但當時只持有 0 股");
	}

	@Test
	void 兩檔異常_還是只寫一筆ERROR_兩檔都列出(CapturedOutput output) {
		Long firstId = addBrokenHolding("2363");
		Long secondId = addBrokenHolding("2317");

		List<String> problems = checker.check("測試");

		assertThat(problems).hasSize(2);
		List<String> lines = tagLines(output);
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0))
				.contains("共 3 檔持股，其中 2 檔交易資料異常")
				.contains("2363（持股 id " + firstId + "）")
				.contains("2317（持股 id " + secondId + "）");
	}

	@Test
	void 沒有異常_只寫INFO_不寫ERROR(CapturedOutput output) {
		List<String> problems = checker.check("測試");

		assertThat(problems).isEmpty();
		List<String> lines = tagLines(output);
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0))
				.contains("INFO")
				.doesNotContain("ERROR")
				.contains("DATA_INTEGRITY 資料檢查（測試）：共 1 檔持股，沒有發現資料異常");
	}

	@Test
	void 檢查本身失敗_寫ERROR_但不丟出例外(CapturedOutput output) {
		// 不用 Spring：直接給一個「讀取就失敗」的查詢服務（模擬資料庫連不上）
		PositionQueryService failing = mock(PositionQueryService.class);
		when(failing.findAll()).thenThrow(new DataAccessResourceFailureException("資料庫連不上"));
		DataIntegrityChecker failingChecker = new DataIntegrityChecker(failing);

		assertThatCode(failingChecker::checkOnStartup).doesNotThrowAnyException();
		assertThatCode(failingChecker::checkDaily).doesNotThrowAnyException();
		assertThat(output.getAll())
				.contains("DATA_INTEGRITY 資料檢查（啟動時）執行失敗")
				.contains("DATA_INTEGRITY 資料檢查（每日排程）執行失敗");
	}

	/**
	 * 新增一檔異常的持股：03-10 買 1,000 股，再直接寫入一筆「03-02 賣出 1,000 股」（比買進還早 → 賣出當時 0 股）。
	 *
	 * @return 持股 id
	 */
	private Long addBrokenHolding(String code) {
		tradeService.buy(code, LocalDate.of(2026, 3, 10), 1000, new BigDecimal("10.00"));
		Long holdingId = holdingRepository.findByCode(code).orElseThrow().getId();
		tradeRepository.save(Trade.create(holdingId, TradeSide.SELL, LocalDate.of(2026, 3, 2), 1000,
				new BigDecimal("10.50"), LocalDateTime.now(clock)));
		return holdingId;
	}

	/**
	 * 這次測試呼叫 check("測試") 寫出的那幾行。
	 * 只抓「（測試）」：Spring 啟動時也會檢查一次（「（啟動時）」），可能被一起抓到，不能算進來。
	 */
	private static List<String> tagLines(CapturedOutput output) {
		return output.getAll().lines()
				.filter(line -> line.contains(DataIntegrityChecker.LOG_TAG + " 資料檢查（測試）"))
				.toList();
	}
}
