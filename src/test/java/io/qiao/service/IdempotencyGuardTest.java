package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import io.qiao.entity.IdempotencyRecord;
import io.qiao.exception.IdempotencyInProgressException;
import io.qiao.exception.IdempotencyKeyUsedException;
import io.qiao.repository.IdempotencyRecordRepository;

/**
 * 佔 key 的流程（朋友回饋 1、2 的修正，B-39 F1 a）。不連資料庫：用假的 repository 確認「每一步做了什麼」。
 *
 * 真正的問題（MySQL 的間隙鎖死結）H2 測不出來，已在真 MySQL 用腳本驗證（WORKLOG）；
 * 這裡守住設計：**key 不存在時不能先刪除**（刪除不存在的列會鎖間隙）、任何一步等鎖逾時都要回 409。
 */
class IdempotencyGuardTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T04:00:00Z"), ZoneId.of("Asia/Taipei"));
	private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
	private static final IdempotencyRequest REQUEST = new IdempotencyRequest("key-1", "a".repeat(64), "POST", "/x", 201);

	private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
	private final IdempotencyGuard guard = new IdempotencyGuard(repository, CLOCK);

	private static IdempotencyRecord recordCreatedAt(LocalDateTime createdAt) {
		return new IdempotencyRecord("key-1", "a".repeat(64), "POST", "/x", 201, createdAt);
	}

	@Test
	void key不存在_直接新增_不先刪除_避免鎖間隙() {
		when(repository.findById("key-1")).thenReturn(Optional.empty());
		when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		IdempotencyRecord record = guard.claim(REQUEST);

		assertThat(record.getId()).isEqualTo("key-1");
		verify(repository, never()).deleteExpired(anyString(), any()); // 朋友回饋 1 的根本原因
	}

	@Test
	void key在有效期限內_不新增_交給API層判斷重送或422() {
		when(repository.findById("key-1")).thenReturn(Optional.of(recordCreatedAt(NOW.minusHours(1))));

		assertThatThrownBy(() -> guard.claim(REQUEST)).isInstanceOf(IdempotencyKeyUsedException.class);
		verify(repository, never()).saveAndFlush(any());
		verify(repository, never()).deleteExpired(anyString(), any());
	}

	@Test
	void key已過期_刪掉那一列再當成新請求() {
		when(repository.findById("key-1")).thenReturn(Optional.of(recordCreatedAt(NOW.minusHours(25))));
		when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		guard.claim(REQUEST);

		verify(repository).deleteExpired(eq("key-1"), eq(NOW.minus(IdempotencyGuard.RETENTION)));
		verify(repository).saveAndFlush(any());
	}

	@Test
	void 同時新增同一個key_對方先提交_主鍵重複() {
		when(repository.findById("key-1")).thenReturn(Optional.empty());
		when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertThatThrownBy(() -> guard.claim(REQUEST)).isInstanceOf(IdempotencyKeyUsedException.class);
	}

	@Test
	void 任何一步等鎖逾時_都回處理中_不會變成500() {
		// 新增時等前一個請求超過 3 秒
		when(repository.findById("key-1")).thenReturn(Optional.empty());
		when(repository.saveAndFlush(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout"));
		assertThatThrownBy(() -> guard.claim(REQUEST)).isInstanceOf(IdempotencyInProgressException.class);

		// 刪除過期的那一列時等鎖逾時（朋友回饋 2：原本這一步在 try 外面 → 500）
		when(repository.findById("key-1")).thenReturn(Optional.of(recordCreatedAt(NOW.minusHours(25))));
		when(repository.deleteExpired(anyString(), any())).thenThrow(new CannotAcquireLockException("Lock wait timeout"));
		assertThatThrownBy(() -> guard.claim(REQUEST)).isInstanceOf(IdempotencyInProgressException.class);
	}
}
