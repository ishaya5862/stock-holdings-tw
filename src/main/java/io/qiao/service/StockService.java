package io.qiao.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Stock;
import io.qiao.quote.StockQuote;
import io.qiao.repository.StockRepository;

/**
 * 股票資訊（名稱、市場別、上次收盤價）的讀寫。只有「官方行情」會寫這裡（新增持股時、背景排程）。
 */
@Service
public class StockService {

	private final StockRepository stockRepository;
	private final Clock clock;

	public StockService(StockRepository stockRepository, Clock clock) {
		this.stockRepository = stockRepository;
		this.clock = clock;
	}

	/**
	 * 寫入官方查到的行情：股票表已有這個代號 → 只更新股票欄位；沒有 → 新增一筆。
	 *
	 * 一律用「只改指定欄位」的 UPDATE，不讀整筆再寫回，也完全不碰持股表：
	 * 使用者同時修改持股（張數、買進價）時，兩邊寫的是不同資料表，不會互相覆寫。
	 * 也用在「上櫃轉上市」（市場別改變）與「往前找到的最後一次成交」。
	 */
	@Transactional
	public void recordQuote(StockQuote quote) {
		LocalDateTime now = LocalDateTime.now(clock);
		int updated = stockRepository.updateQuote(quote.code(), quote.name(), quote.market(),
				quote.closePrice(), quote.priceDate(), now);
		if (updated == 0) {
			stockRepository.saveAndFlush(new Stock(quote.code(), quote.name(), quote.market(),
					quote.closePrice(), quote.priceDate(), now));
		}
	}

	/** 目前有人持有的股票（背景排程要更新的對象） */
	@Transactional(readOnly = true)
	public List<Stock> findHeld() {
		return stockRepository.findHeld();
	}

	/** 一次查出多個代號的股票資訊（清單頁用：查詢次數固定，不會每一筆持股各查一次） */
	@Transactional(readOnly = true)
	public Map<String, Stock> findByCodes(Collection<String> codes) {
		return stockRepository.findAllById(codes).stream()
				.collect(Collectors.toMap(Stock::getCode, Function.identity()));
	}
}
