package io.qiao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.qiao.calculation.TradeInput;
import io.qiao.calculation.TradeSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 交易紀錄（對應資料表 trades，建表 SQL 見 db/migration/V5）。2.0 第 6 題。
 *
 * 只存原始事實（買或賣、日期、股數、價格）；手續費、證交稅、已實現損益不存，每次由 PositionCalculator 重算。
 * 和持股用 holdingId 對應（不用 JPA 關聯），和 Holding、Stock 的做法一致：
 * 避免關閉 open-in-view 後讀到未載入資料的錯誤，查詢次數也固定。
 *
 * version 欄位（資料庫已有）暫不對應：C3 設計「手機與電腦同時操作」時才加上 @Version（C2-5 決定）。
 */
@Entity
@Table(name = "trades")
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "holding_id", nullable = false)
	private Long holdingId;

	/**
	 * 買進或賣出。加 @JdbcTypeCode(VARCHAR)：Hibernate 6 在 MySQL 上預設把 enum 對應成 MySQL 的 ENUM 型別，
	 * 和資料表的 VARCHAR 不符，ddl-auto=validate 會啟動失敗（第 2 題遇過，見 Stock.market）。
	 */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 4)
	private TradeSide side;

	@Column(name = "trade_date", nullable = false)
	private LocalDate tradeDate;

	/** 日期是推估的：1.0 轉入（V5）、App 1.0 新增時沒有日期，用當天日期代替（D3） */
	@Column(name = "date_estimated", nullable = false)
	private boolean dateEstimated;

	@Column(nullable = false)
	private Long shares;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	/** 建立、修改時間由 Service 用台北時區的 Clock 設定，不用主機時間 */
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * App 1.0 新增持股時的那一筆買進（v1 只有張數、買進價，沒有日期 → 用台北時區的今天，標記推估）。
	 */
	public static Trade v1Buy(Long holdingId, int lots, BigDecimal price, LocalDateTime now) {
		Trade trade = new Trade();
		trade.holdingId = holdingId;
		trade.side = TradeSide.BUY;
		trade.tradeDate = now.toLocalDate();
		trade.dateEstimated = true;
		trade.shares = (long) lots * 1000;
		trade.price = price;
		trade.createdAt = now;
		trade.updatedAt = now;
		return trade;
	}

	/**
	 * 一筆有日期的交易（2.0 的買進、賣出；日期是使用者填的，不是推估）。
	 * C3 的交易服務會用它；C4 的測試先用它造出「多次買進、有賣出」的資料。
	 */
	public static Trade create(Long holdingId, TradeSide side, LocalDate tradeDate, long shares, BigDecimal price,
			LocalDateTime now) {
		Trade trade = new Trade();
		trade.holdingId = holdingId;
		trade.side = side;
		trade.tradeDate = tradeDate;
		trade.dateEstimated = false;
		trade.shares = shares;
		trade.price = price;
		trade.createdAt = now;
		trade.updatedAt = now;
		return trade;
	}

	/** 轉成計算核心用的輸入（PositionCalculator 不依賴資料庫） */
	public TradeInput toInput() {
		return new TradeInput(id, side, tradeDate, shares, price);
	}

	public Long getId() {
		return id;
	}

	public Long getHoldingId() {
		return holdingId;
	}

	public TradeSide getSide() {
		return side;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public boolean isDateEstimated() {
		return dateEstimated;
	}

	public Long getShares() {
		return shares;
	}

	public void setShares(Long shares) {
		this.shares = shares;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
}
