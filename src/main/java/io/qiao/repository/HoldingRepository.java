package io.qiao.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.qiao.entity.Holding;
import jakarta.persistence.LockModeType;

/**
 * 持股資料存取。方法名稱照 Spring Data JPA 的命名規則，SQL 會自動產生。
 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	/** 代號是否已存在（新增前檢查重複用） */
	boolean existsByCode(String code);

	/** 依代號找持股（App 1.0 新增已全部賣光的代號時，沿用原本的持股列） */
	Optional<Holding> findByCode(String code);

	/** 清單依代號排序，畫面上比較好找 */
	List<Holding> findAllByOrderByCodeAsc();

	/**
	 * 依代號讀取並鎖住這一列（SELECT … FOR UPDATE，悲觀鎖，第 6 題 C3-1）。
	 * 寫入這檔的交易前呼叫：手機與電腦同時寫同一檔時，後到的會等前一個交易結束，
	 * 再讀到最新的交易紀錄重算，才不會兩邊各自檢查都沒賣超、合起來卻賣超。
	 * 等超過 3 秒（connection-init-sql）會丟出 PessimisticLockingFailureException。
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from Holding h where h.code = :code")
	Optional<Holding> findByCodeForUpdate(@Param("code") String code);

	/** 依 id 讀取並鎖住這一列（修改、刪除交易，以及 1.0 的修改、刪除用） */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from Holding h where h.id = :id")
	Optional<Holding> findByIdForUpdate(@Param("id") Long id);
}
