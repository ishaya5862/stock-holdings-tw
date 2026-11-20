package io.qiao.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.IdempotencyRecord;
import io.qiao.exception.IdempotencyInProgressException;
import io.qiao.exception.IdempotencyKeyUsedException;
import io.qiao.repository.IdempotencyRecordRepository;

/**
 * 防止重送（冪等）的資料庫部分，2.0 第 6 題 C7。
 *
 * 做法（C7-2 a）：在「寫入交易的同一個資料庫交易」裡先佔住 key（新增一列，主鍵 = key）：
 * - 交易成功 → key 和交易一起提交；交易失敗（例如賣超）→ key 也一起復原，重送時重新執行。
 *   不會有「交易記了、key 沒記」或反過來的空窗。
 * - 同一個 key 已經做完 → 主鍵重複 → IdempotencyKeyUsedException（API 層回傳那次的結果）。
 * - 同一個 key 剛好同時到達 → 資料庫讓第二個等第一個提交，再得到主鍵重複（同上）；
 *   等超過 3 秒（等鎖上限）→ IdempotencyInProgressException（409，請稍後用同一個 key 再送）。
 *
 * 有效期限 24 小時（B-27：涵蓋所有合理的重送，表不會無限變大）；過期的同一個 key 當成新請求。
 */
@Component
public class IdempotencyGuard {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

	/** key 保存多久 */
	public static final Duration RETENTION = Duration.ofHours(24);

	private final IdempotencyRecordRepository repository;
	private final Clock clock;

	public IdempotencyGuard(IdempotencyRecordRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * 佔住 key（必須在寫入交易的同一個資料庫交易內呼叫）。
	 *
	 * @return 新增的紀錄；寫入完成後呼叫 complete() 記下結果，提交時一起寫入
	 * @throws IdempotencyKeyUsedException    這個 key 已經用過
	 * @throws IdempotencyInProgressException 同一個 key 的前一個請求還在處理，等超過 3 秒
	 */
	public IdempotencyRecord claim(IdempotencyRequest request) {
		LocalDateTime now = LocalDateTime.now(clock);
		repository.deleteExpired(request.key(), now.minus(RETENTION)); // 過期的同一個 key → 當成新請求
		try {
			return repository.saveAndFlush(new IdempotencyRecord(request.key(), request.requestHash(),
					request.method(), request.path(), request.responseStatus(), now));
		} catch (DataIntegrityViolationException e) {
			throw new IdempotencyKeyUsedException(request.key());
		} catch (PessimisticLockingFailureException e) {
			throw new IdempotencyInProgressException(e);
		}
	}

	/**
	 * 寫入完成後記下結果（同一個資料庫交易內，提交時一起寫入）。
	 *
	 * 為什麼要明確 save，不能只改欄位：寫入過程中有些更新語法會清空 JPA 的暫存區（例如記錄行情的
	 * StockRepository.updateQuote 設了 clearAutomatically），清空後 record 不再被追蹤，只改欄位不會寫進資料庫，
	 * 重送時就查不到寫了哪一筆。save（merge）不管 record 還有沒有被追蹤都會寫入。
	 */
	public void complete(IdempotencyRecord record, Long tradeId, String holdingCode, boolean holdingDeleted) {
		record.complete(tradeId, holdingCode, holdingDeleted);
		repository.save(record);
	}

	/** 查詢 key 的紀錄（API 層用來判斷「重送」或「同 key 不同內容」）；過期的視為不存在 */
	@Transactional(readOnly = true)
	public Optional<IdempotencyRecord> find(String key) {
		LocalDateTime expiredBefore = LocalDateTime.now(clock).minus(RETENTION);
		return repository.findById(key).filter(record -> !record.getCreatedAt().isBefore(expiredBefore));
	}

	/** 每小時清除過期的 key（第一次在啟動一小時後） */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
	@Transactional
	public void purgeExpired() {
		int deleted = repository.deleteAllExpired(LocalDateTime.now(clock).minus(RETENTION));
		if (deleted > 0) {
			log.info("已清除 {} 個過期的 Idempotency-Key", deleted);
		}
	}
}
