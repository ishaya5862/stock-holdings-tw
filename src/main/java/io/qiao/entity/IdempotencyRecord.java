package io.qiao.entity;

import java.time.LocalDateTime;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 一個已經處理過的 Idempotency-Key（對應資料表 idempotency_keys，V7）。2.0 第 6 題 C7。
 *
 * 為什麼實作 Persistable：主鍵是 App 給的 key（不是自動編號），Spring Data 遇到「已經有 id」的物件，
 * 預設會當成「可能已存在」→ 先查詢、存在就「更新」。這樣同一個 key 第二次寫入會直接蓋掉第一次的紀錄，
 * 資料庫的唯一限制完全派不上用場。isNew() 回傳 true → 一律「新增」，重複的 key 由資料庫擋下（主鍵重複）。
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord implements Persistable<String> {

	@Id
	@Column(name = "idempotency_key", length = 64)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Column(nullable = false, length = 10)
	private String method;

	@Column(nullable = false, length = 200)
	private String path;

	@Column(name = "trade_id")
	private Long tradeId;

	@Column(name = "holding_code", length = 10)
	private String holdingCode;

	@Column(name = "holding_deleted", nullable = false)
	private boolean holdingDeleted;

	@Column(name = "response_status", nullable = false)
	private int responseStatus;

	/** 台北時區的 Clock 設定，不用主機時間 */
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** 剛建立、還沒存進資料庫 → 新增（從資料庫讀出來的不是新的） */
	@Transient
	private boolean isNew = true;

	protected IdempotencyRecord() {
		// JPA 需要
	}

	public IdempotencyRecord(String key, String requestHash, String method, String path, int responseStatus,
			LocalDateTime createdAt) {
		this.idempotencyKey = key;
		this.requestHash = requestHash;
		this.method = method;
		this.path = path;
		this.responseStatus = responseStatus;
		this.createdAt = createdAt;
	}

	/** 寫入完成後記下結果（同一個資料庫交易內，提交時一起寫入） */
	public void complete(Long tradeId, String holdingCode, boolean holdingDeleted) {
		this.tradeId = tradeId;
		this.holdingCode = holdingCode;
		this.holdingDeleted = holdingDeleted;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	@Override
	public String getId() {
		return idempotencyKey;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getTradeId() {
		return tradeId;
	}

	public String getHoldingCode() {
		return holdingCode;
	}

	public boolean isHoldingDeleted() {
		return holdingDeleted;
	}

	public int getResponseStatus() {
		return responseStatus;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
