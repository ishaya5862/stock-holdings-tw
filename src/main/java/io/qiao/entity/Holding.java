package io.qiao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 持股（對應資料表 holdings，建表 SQL 見 db/migration）。
 *
 * 只放「使用者會修改」的欄位：代號、張數、買進價、建立／修改時間。
 * 股票名稱、市場別、上次收盤價屬於股票本身，V3／V4 已搬到 stocks（見 Stock）：
 * 背景排程只寫 stocks、使用者只改 holdings，兩邊不會互相覆寫。
 * 兩張表用「代號」對應（holdings.code → stocks.code 外鍵），程式裡由 Service 依代號配對，
 * 不用 JPA 關聯，避免關閉 open-in-view 後在畫面上讀到未載入資料的錯誤（LazyInitializationException）。
 */
@Entity
@Table(name = "holdings")
public class Holding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 股票代號：用字串，保留開頭的 0（如 0050）；對應 stocks.code */
	@Column(nullable = false, length = 10, unique = true)
	private String code;

	/** 張數（1 張 = 1,000 股） */
	@Column(nullable = false)
	private Integer lots;

	/** 買進價（每股） */
	@Column(name = "buy_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal buyPrice;

	/** 建立、修改時間由 Service 用台北時區的 Clock 設定，不用主機時間 */
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Integer getLots() {
		return lots;
	}

	public void setLots(Integer lots) {
		this.lots = lots;
	}

	public BigDecimal getBuyPrice() {
		return buyPrice;
	}

	public void setBuyPrice(BigDecimal buyPrice) {
		this.buyPrice = buyPrice;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
