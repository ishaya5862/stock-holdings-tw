package io.qiao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 持股（對應資料表 holdings，建表 SQL 見 db/migration）。
 */
@Entity
@Table(name = "holdings")
public class Holding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 股票代號：用字串，保留開頭的 0（如 0050） */
	@Column(nullable = false, length = 10, unique = true)
	private String code;

	/** 股票名稱：第 3 題串接官方行情後才有值 */
	@Column(length = 50)
	private String name;

	/**
	 * 市場別：第 3 題串接官方行情後才有值。
	 * 加 @JdbcTypeCode(VARCHAR) 的原因：Hibernate 6 在 MySQL 上預設把 enum 對應成 MySQL 的 ENUM 型別，
	 * 但資料表是 VARCHAR(4)，啟動時的資料表檢查（ddl-auto=validate）會失敗。
	 */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 4)
	private Market market;

	/** 張數（1 張 = 1,000 股） */
	@Column(nullable = false)
	private Integer lots;

	/** 買進價（每股） */
	@Column(name = "buy_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal buyPrice;

	/** 最後一次成功取得的收盤價：只用於取不到行情時顯示「上次收盤價」（第 3 題使用） */
	@Column(name = "last_close_price", precision = 10, scale = 2)
	private BigDecimal lastClosePrice;

	@Column(name = "last_price_date")
	private LocalDate lastPriceDate;

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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Market getMarket() {
		return market;
	}

	public void setMarket(Market market) {
		this.market = market;
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

	public BigDecimal getLastClosePrice() {
		return lastClosePrice;
	}

	public void setLastClosePrice(BigDecimal lastClosePrice) {
		this.lastClosePrice = lastClosePrice;
	}

	public LocalDate getLastPriceDate() {
		return lastPriceDate;
	}

	public void setLastPriceDate(LocalDate lastPriceDate) {
		this.lastPriceDate = lastPriceDate;
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
