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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 股票（對應資料表 stocks，V3 建立）：名稱、市場別、最後一次取得的收盤價。
 *
 * 只有「官方行情」會寫這張表（新增持股時、背景排程），使用者修改持股只寫 holdings，
 * 兩邊不會寫到同一列資料，所以不會互相覆寫（修正遺失更新的問題）。
 * 一般更新一律用 StockRepository 的「只改指定欄位」UPDATE，不讀整筆再寫回。
 */
@Entity
@Table(name = "stocks")
public class Stock {

	/** 股票代號就是主鍵（一個代號一筆） */
	@Id
	@Column(length = 10)
	private String code;

	/** 股票名稱；第 2 題時期的舊資料可能還沒查到 */
	@Column(length = 50)
	private String name;

	/**
	 * 市場別。加 @JdbcTypeCode(VARCHAR) 的原因：Hibernate 6 在 MySQL 上預設把 enum 對應成 MySQL 的 ENUM 型別，
	 * 但資料表是 VARCHAR(4)，啟動時的資料表檢查（ddl-auto=validate）會失敗。
	 */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 4)
	private Market market;

	/** 最後一次成功取得的收盤價：取不到行情時顯示「上次收盤價」 */
	@Column(name = "last_close_price", precision = 10, scale = 2)
	private BigDecimal lastClosePrice;

	@Column(name = "last_price_date")
	private LocalDate lastPriceDate;

	/** 最後一次寫入的時間（台北時間） */
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Stock() {
		// JPA 需要無參數建構子
	}

	public Stock(String code, String name, Market market, BigDecimal lastClosePrice, LocalDate lastPriceDate,
			LocalDateTime updatedAt) {
		this.code = code;
		this.name = name;
		this.market = market;
		this.lastClosePrice = lastClosePrice;
		this.lastPriceDate = lastPriceDate;
		this.updatedAt = updatedAt;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public Market getMarket() {
		return market;
	}

	public BigDecimal getLastClosePrice() {
		return lastClosePrice;
	}

	public LocalDate getLastPriceDate() {
		return lastPriceDate;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
