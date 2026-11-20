package io.qiao.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.qiao.entity.Market;
import io.qiao.entity.Stock;

/**
 * 股票資料存取。
 *
 * 寫入一律用「只改指定欄位」的 UPDATE（@Modifying @Query），不採「讀整筆 → 改 → 交易結束自動寫回」：
 * 後者 Hibernate 會把整列所有欄位寫回，和其他交易同時修改時會互相覆寫（遺失更新）。
 */
public interface StockRepository extends JpaRepository<Stock, String> {

	/**
	 * 寫入官方查到的股票資訊與收盤價（只改這幾個欄位）。
	 *
	 * @return 更新了幾筆（0 = 股票表還沒有這個代號）
	 */
	// flushAutomatically：執行前先把同一交易裡還沒寫入的變更送出；clearAutomatically：執行後清掉暫存的舊 Entity，
	// 避免同一交易後面讀到 UPDATE 之前的舊值
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			UPDATE Stock s
			   SET s.name = :name, s.market = :market,
			       s.lastClosePrice = :closePrice, s.lastPriceDate = :priceDate, s.updatedAt = :now
			 WHERE s.code = :code
			""")
	int updateQuote(@Param("code") String code, @Param("name") String name, @Param("market") Market market,
			@Param("closePrice") BigDecimal closePrice, @Param("priceDate") LocalDate priceDate,
			@Param("now") LocalDateTime now);

	/**
	 * 目前有人持有的股票（背景排程要更新的對象），依代號排序。
	 * 用子查詢只挑出持股表裡有的代號：股票表裡已經沒人持有的代號不用再查官方。
	 */
	@Query("SELECT s FROM Stock s WHERE s.code IN (SELECT h.code FROM Holding h) ORDER BY s.code")
	List<Stock> findHeld();
}
