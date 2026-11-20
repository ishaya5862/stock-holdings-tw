package io.qiao.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.qiao.entity.IdempotencyRecord;

/**
 * Idempotency-Key 紀錄的資料存取（2.0 第 6 題 C7）。
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

	/**
	 * 刪除這個 key 已過期的舊紀錄（同一個 key 過期後再用，當成新請求）。
	 * clearAutomatically：刪除語法直接改資料庫、不會更新 JPA 暫存區；暫存區若還留著舊紀錄，
	 * 接著新增同一個 key 會被 JPA 擋下。這是佔 key 的第一步，清掉暫存區不影響其他東西。
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from IdempotencyRecord r where r.idempotencyKey = :key and r.createdAt < :expiredBefore")
	int deleteExpired(@Param("key") String key, @Param("expiredBefore") LocalDateTime expiredBefore);

	/** 刪除所有過期的紀錄（每小時清除一次） */
	@Modifying
	@Query("delete from IdempotencyRecord r where r.createdAt < :expiredBefore")
	int deleteAllExpired(@Param("expiredBefore") LocalDateTime expiredBefore);
}
