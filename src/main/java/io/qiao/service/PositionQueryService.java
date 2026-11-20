package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.calculation.PositionCalculator;
import io.qiao.calculation.PositionResult;
import io.qiao.entity.Holding;
import io.qiao.entity.Market;
import io.qiao.entity.Stock;
import io.qiao.entity.Trade;
import io.qiao.exception.NotHeldException;
import io.qiao.quote.QuoteCache;
import io.qiao.quote.QuoteStatus;
import io.qiao.quote.StockQuote;
import io.qiao.repository.HoldingRepository;
import io.qiao.repository.TradeRepository;
import io.qiao.validation.StockCodes;

/**
 * 2.0 的讀取（第 6 題 C5）：持股＋股票資訊＋行情快取＋由交易紀錄重算的結果。App 2.0（/api/v2）與新網頁共用。
 *
 * - 0 股（全部賣光）的持股也會回傳（C5-4 a），由用戶端決定要不要顯示；已實現損益才看得到來源。
 * - 只讀資料庫與記憶體快取，不會向官方查詢（題目第 3 題要求 3）。
 * - 每個方法都在同一個唯讀交易裡讀持股、股票、交易：另一個裝置同時寫入時，讀到的是同一個時間點的資料，數字彼此一致。
 * - 持股、股票、交易各查一次，在記憶體配對重算，不會每一筆持股各查一次。
 */
@Service
public class PositionQueryService {

	private final HoldingRepository holdingRepository;
	private final TradeRepository tradeRepository;
	private final StockService stockService;
	private final QuoteCache quoteCache;
	private final PositionCalculator positionCalculator;
	private final Clock clock;

	public PositionQueryService(HoldingRepository holdingRepository, TradeRepository tradeRepository,
			StockService stockService, QuoteCache quoteCache, PositionCalculator positionCalculator, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.tradeRepository = tradeRepository;
		this.stockService = stockService;
		this.quoteCache = quoteCache;
		this.positionCalculator = positionCalculator;
		this.clock = clock;
	}

	/** 全部持股（含 0 股），依代號排序 */
	@Transactional(readOnly = true)
	public List<PositionView> findAll() {
		List<Holding> holdings = holdingRepository.findAllByOrderByCodeAsc();
		Map<String, Stock> stocks = stockService.findByCodes(holdings.stream().map(Holding::getCode).toList());
		Map<Long, List<Trade>> tradesByHolding = tradeRepository.findAllByOrderByHoldingIdAscTradeDateAscIdAsc().stream()
				.collect(Collectors.groupingBy(Trade::getHoldingId));
		LocalDate today = LocalDate.now(clock);
		return holdings.stream()
				.map(holding -> toView(holding, stocks.get(holding.getCode()),
						tradesByHolding.getOrDefault(holding.getId(), List.of()), today))
				.toList();
	}

	/**
	 * 單一持股（代號會先統一寫法：全形轉半形、轉大寫）。
	 *
	 * @throws NotHeldException 從沒持有過這個代號（或最後一筆交易已刪除）
	 */
	@Transactional(readOnly = true)
	public PositionView findByCode(String code) {
		String normalizedCode = StockCodes.normalize(code);
		Holding holding = holdingRepository.findByCode(normalizedCode)
				.orElseThrow(() -> new NotHeldException(normalizedCode));
		Stock stock = stockService.findByCodes(List.of(normalizedCode)).get(normalizedCode);
		List<Trade> trades = tradeRepository.findByHoldingIdOrderByTradeDateAscIdAsc(holding.getId());
		return toView(holding, stock, trades, LocalDate.now(clock));
	}

	private PositionView toView(Holding holding, Stock stock, List<Trade> trades, LocalDate today) {
		QuoteCache.Entry entry = quoteCache.get(holding.getCode()).orElse(null);
		QuoteStatus status = entry == null ? QuoteStatus.UPDATING : entry.status(); // 快取沒有 = 這次啟動後還沒查過
		StockQuote quote = entry == null ? null : entry.quote();
		// 只有查詢成功（OK）才算未實現損益；和 1.0 的規則相同
		BigDecimal priceForProfit = status == QuoteStatus.OK && quote != null ? quote.closePrice() : null;
		PositionResult position = positionCalculator.calculate(holding.getCode(),
				trades.stream().map(Trade::toInput).toList(), priceForProfit, today);
		return new PositionView(holding, stock, status, quote, trades, position);
	}

	/**
	 * 一筆持股的讀取結果。
	 *
	 * @param stock    股票資訊（stocks；外鍵保證有，仍容許 null）
	 * @param status   行情狀態（OK／UPDATING／UNAVAILABLE／NO_RECENT_TRADE）
	 * @param quote    快取裡的行情；沒有時為 null
	 * @param trades   這檔的全部交易（依日期、id 排序；和 position.lines() 一一對應）
	 * @param position 重算結果（股數、成本、已實現、未實現、每一筆交易的明細）
	 */
	public record PositionView(
			Holding holding,
			Stock stock,
			QuoteStatus status,
			StockQuote quote,
			List<Trade> trades,
			PositionResult position) {

		/** 名稱、市場以官方為準：快取有值就用快取（這次啟動後查到的最新資料），否則用股票表（和 1.0 相同） */
		public String name() {
			return quote != null ? quote.name() : (stock != null ? stock.getName() : null);
		}

		public Market market() {
			return quote != null ? quote.market() : (stock != null ? stock.getMarket() : null);
		}
	}
}
