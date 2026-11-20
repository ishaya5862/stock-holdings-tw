package io.qiao.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.qiao.entity.Trade;

/**
 * 交易紀錄資料存取。方法名稱照 Spring Data JPA 的命名規則，SQL 會自動產生。
 */
public interface TradeRepository extends JpaRepository<Trade, Long> {

	/** 某一筆持股的全部交易，依重算順序（交易日期 → id）排列；用得到索引 idx_trades_holding_order */
	List<Trade> findByHoldingIdOrderByTradeDateAscIdAsc(Long holdingId);

	/** 全部交易，依持股 → 交易日期 → id 排列（清單一次查出、在記憶體分組重算，不會每筆持股各查一次） */
	List<Trade> findAllByOrderByHoldingIdAscTradeDateAscIdAsc();

	/**
	 * 某一筆交易屬於哪一筆持股（只查一個數字，不載入 Entity）。
	 * 修改、刪除交易時先用它找到持股並鎖住，鎖住之後才載入交易本身，讀到的才是最新版本。
	 */
	@Query("select t.holdingId from Trade t where t.id = :id")
	Optional<Long> findHoldingIdById(@Param("id") Long id);

	/** 刪除某一筆持股的全部交易（刪持股前呼叫：外鍵不連帶刪除，由程式明確先刪交易） */
	void deleteByHoldingId(Long holdingId);
}
