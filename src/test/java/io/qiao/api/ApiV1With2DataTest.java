package io.qiao.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.TradeSide;
import io.qiao.entity.Market;
import io.qiao.entity.Trade;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.repository.TradeRepository;
import io.qiao.service.HoldingService;

/**
 * App 1.0 API 看 2.0 的資料（第 6 題 C4）：App 1.0 已發布、不會再改，2.0 的零股、多次買進、賣出要「換成 1.0 的樣子」。
 *
 * 決定（PARTB_NOTES B-18）：
 * - C4-1 lots 無條件捨去；C4-2 buy_price = 平均買進價（不含手續費）；C4-6 金額每筆先四捨五入到元
 * - C4-3／C4-4 1.0 表達不了的持股不能修改、刪除 → 409 UPGRADE_REQUIRED，訊息請使用者更新
 * - C4-5 全部賣光的持股 1.0 看不到；新增同代號沿用持股列（201）
 * - C4-7 name 加註「【請更新 App 2.0：…】」；M3 Deprecation／Link 標頭；M4 X-Client: app2 回 400
 *
 * 2.0 的交易服務在 C3 才做，這裡直接用 TradeRepository 造出多次買進、賣出的資料。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiV1With2DataTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private Clock clock;

	@MockitoBean
	private StockLookupService stockLookupService;

	@BeforeEach
	void setUp() {
		// 官方行情：台積電收盤 650（新增時放進快取，狀態 OK）
		when(stockLookupService.lookup(anyString())).thenAnswer(invocation -> new StockQuote(
				invocation.getArgument(0), "台積電", Market.TWSE, new BigDecimal("650.00"), LocalDate.of(2026, 11, 20)));
	}

	/**
	 * 台積電：1.0 新增 1 張 @600，2.0 再買 500 股 @620、賣 300 股 @700 → 剩 1,200 股。
	 *
	 * 手算：
	 * - 買 1,000 @600：600,000 + 手續費 855 = 600,855
	 * - 買 500 @620：310,000 + 手續費 441（441.75 捨去）= 310,441 → 1,500 股、成本 911,296、買進金額 910,000
	 * - 賣 300：分攤成本 911,296 × 300 ÷ 1,500 = 182,259.20 → 剩 729,036.80；分攤買進金額 182,000 → 剩 728,000
	 * - lots = 1,200 ÷ 1,000 捨去 = 1；buy_price = 728,000 ÷ 1,200 = 606.666… → 606.67
	 * - 收盤 650：市值 780,000 − 手續費 1,111（1,111.5 捨去）− 證交稅 2,340 − 729,036.80 = 47,512.20 → pnl 47,512
	 * - 收益率 47,512.20 ÷ 729,036.80 = 6.517…% → 6.52；total_cost 729,036.80 → 729,037
	 */
	private Long createTsmcWith2Trades() {
		Long id = holdingService.create("2330", 1, new BigDecimal("600")).getId();
		LocalDate today = LocalDate.now(clock);
		LocalDateTime now = LocalDateTime.now(clock);
		tradeRepository.save(Trade.create(id, TradeSide.BUY, today, 500, new BigDecimal("620.00"), now));
		tradeRepository.save(Trade.create(id, TradeSide.SELL, today, 300, new BigDecimal("700.00"), now));
		return id;
	}

	// ===== 讀取：換成 1.0 的樣子 =====

	@Test
	void 零股多次買進有賣出_lots捨去_buyPrice是平均買進價_金額捨入到元_名稱加註() throws Exception {
		Long id = createTsmcWith2Trades();

		mockMvc.perform(get("/api/v1/holdings/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.intValue())) // App 1.0 記住的 id 不變
				.andExpect(jsonPath("$.name").value("台積電【請更新 App 2.0：含零股 200 股、有多次買進、有賣出紀錄】"))
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.buy_price").value(606.67))
				.andExpect(jsonPath("$.current_price").value(650.00))
				.andExpect(jsonPath("$.pnl").value(47512))
				.andExpect(jsonPath("$.pnl").value(Matchers.isA(Integer.class))) // 規格：金額是 JSON 整數
				.andExpect(jsonPath("$.return_pct").value(6.52));
	}

	@Test
	void 總計用每筆捨入後的金額加總_清單加起來等於總計() throws Exception {
		createTsmcWith2Trades();

		// total_cost 729,036.80 → 729,037；pnl 47,512.20 → 47,512；收益率 47,512 ÷ 729,037 = 6.5170…% → 6.52
		mockMvc.perform(get("/api/v1/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_cost").value(729037))
				.andExpect(jsonPath("$.market_value").value(780000))
				.andExpect(jsonPath("$.pnl").value(47512))
				.andExpect(jsonPath("$.return_pct").value(6.52))
				.andExpect(jsonPath("$.quote_missing").value(0));
	}

	@Test
	void 一點零的資料_名稱不加註_數字和升級前完全相同() throws Exception {
		// 第 4 題例子 1：1 張 @1000、收盤 1100 → 93,708、9.36%（收盤價改成 1100）
		when(stockLookupService.lookup(anyString())).thenReturn(new StockQuote(
				"2330", "台積電", Market.TWSE, new BigDecimal("1100.00"), LocalDate.of(2026, 11, 20)));
		Long id = holdingService.create("2330", 1, new BigDecimal("1000")).getId();

		mockMvc.perform(get("/api/v1/holdings/{id}", id))
				.andExpect(jsonPath("$.name").value("台積電"))
				.andExpect(jsonPath("$.lots").value(1))
				.andExpect(jsonPath("$.buy_price").value(1000.00))
				.andExpect(jsonPath("$.pnl").value(93708))
				.andExpect(jsonPath("$.return_pct").value(9.36));
	}

	@Test
	void App修改畫面原樣送回_數字不變() throws Exception {
		// App 1.0 的修改畫面：先 GET 拿到張數、買進價，使用者沒改就按儲存（PB11：buy_price 不含手續費才不會重複算）
		Long id = holdingService.create("2330", 2, new BigDecimal("1000.50")).getId();

		mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":2,\"buy_price\":1000.50}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.buy_price").value(1000.50));

		Trade trade = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id).get(0);
		assertThat(trade.getShares()).isEqualTo(2000L);
		assertThat(trade.getPrice()).isEqualByComparingTo("1000.50");
	}

	// ===== 寫入：1.0 表達不了的持股不能改、不能刪 =====

	@Test
	void 修改有多筆交易的持股_409要求更新_交易紀錄不變() throws Exception {
		Long id = createTsmcWith2Trades();

		mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":1,\"buy_price\":600}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("UPGRADE_REQUIRED"))
				.andExpect(jsonPath("$.error.message").value(
						"2330 在 2.0 含零股 200 股、有多次買進、有賣出紀錄，App 1.0 無法修改，請更新到 App 2.0 後再操作"));

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id)).hasSize(3);
	}

	@Test
	void 刪除有多筆交易的持股_409要求更新_不會誤刪2點0的紀錄() throws Exception {
		Long id = createTsmcWith2Trades();

		mockMvc.perform(delete("/api/v1/holdings/{id}", id))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("UPGRADE_REQUIRED"))
				.andExpect(jsonPath("$.error.message").value(Matchers.containsString("App 1.0 無法刪除")));

		assertThat(tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id)).hasSize(3);
	}

	@Test
	void 只有一筆零股買進_可以刪除_但不能修改() throws Exception {
		// C4-4：只有一筆交易 → 刪除不會丟失任何 2.0 的歷史；C4-3：零股 1.0 表達不了 → 不能修改
		Long id = holdingService.create("2330", 1, new BigDecimal("600")).getId();
		Trade only = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(id).get(0);
		only.setShares(300L); // 模擬 2.0 只買了 300 股

		mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":1,\"buy_price\":600}"))
				.andExpect(status().isConflict());
		mockMvc.perform(delete("/api/v1/holdings/{id}", id))
				.andExpect(status().isNoContent());
	}

	// ===== 全部賣光（0 股） =====

	@Test
	void 全部賣光_清單不顯示_單筆404_新增同代號沿用原本的id() throws Exception {
		Long id = holdingService.create("2330", 1, new BigDecimal("600")).getId();
		tradeRepository.save(Trade.create(id, TradeSide.SELL, LocalDate.now(clock), 1000, new BigDecimal("650.00"),
				LocalDateTime.now(clock)));

		mockMvc.perform(get("/api/v1/holdings")).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/v1/holdings/{id}", id)).andExpect(status().isNotFound());
		mockMvc.perform(put("/api/v1/holdings/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lots\":1,\"buy_price\":600}")).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/v1/holdings/{id}", id)).andExpect(status().isNotFound());

		// 1.0 使用者看不到這檔 → 重新新增不應該被說「已在持股中」：沿用持股列、加一筆買進
		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2330\",\"lots\":2,\"buy_price\":620}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id.intValue()))
				.andExpect(jsonPath("$.lots").value(2))
				.andExpect(jsonPath("$.buy_price").value(620.00)); // 只算剩下的股票（新買的 2 張）
	}

	@Test
	void 還有股數時新增同代號_照規格409() throws Exception {
		createTsmcWith2Trades();

		mockMvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":600}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("DUPLICATE_CODE"));
	}

	// ===== M3 已淘汰標頭、M4 App 2.0 誤用 =====

	@Test
	void 每個v1回應都帶Deprecation與Link標頭_錯誤回應也有() throws Exception {
		String expectedDeprecation = "@" + LocalDateTime.of(2026, 11, 21, 0, 0)
				.atZone(java.time.ZoneId.of("Asia/Taipei")).toEpochSecond();

		mockMvc.perform(get("/api/v1/holdings"))
				.andExpect(header().string("Deprecation", expectedDeprecation))
				.andExpect(header().string("Link", Matchers.containsString("rel=\"deprecation\"")))
				.andExpect(header().string("Link", Matchers.containsString("API.md")));
		mockMvc.perform(get("/api/v1/holdings/{id}", 99999))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Deprecation", expectedDeprecation));
	}

	@Test
	void App2點0帶XClient呼叫v1_400要求改用v2_錯誤格式照v1規格() throws Exception {
		mockMvc.perform(get("/api/v1/holdings").header("X-Client", "App2/2.0.0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("USE_API_V2"))
				.andExpect(jsonPath("$.error.message").value(Matchers.containsString("/api/v2")));
	}

	@Test
	void 沒有XClient或其他用戶端_照常可以呼叫v1() throws Exception {
		mockMvc.perform(get("/api/v1/holdings")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/holdings").header("X-Client", "app1")).andExpect(status().isOk());
	}
}
