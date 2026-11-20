package io.qiao.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.qiao.entity.Holding;

/**
 * 持股資料存取。方法名稱照 Spring Data JPA 的命名規則，SQL 會自動產生。
 */
public interface HoldingRepository extends JpaRepository<Holding, Long> {

	/** 代號是否已存在（新增前檢查重複用） */
	boolean existsByCode(String code);

	/** 清單依代號排序，畫面上比較好找 */
	List<Holding> findAllByOrderByCodeAsc();
}
