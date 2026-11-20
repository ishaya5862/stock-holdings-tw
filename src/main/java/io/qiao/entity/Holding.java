package io.qiao.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 持股（對應資料表 holdings，建表 SQL 見 db/migration）。每一檔股票一筆。
 *
 * 2.0（第 6 題）起只放「這檔持股是誰」：id（App 1.0 用它修改、刪除，一直不變）、代號、建立／修改時間。
 * 張數、買進價不再存（V6 刪除）：改由交易紀錄（trades）依日期重算（PositionCalculator），
 * 資料只有一份，不會有「存的張數和交易紀錄對不起來」的問題（資料表設計 N1，完全正規化）。
 *
 * 股票名稱、市場別、上次收盤價屬於股票本身，在 stocks（見 Stock）：背景排程只寫 stocks，不會和使用者互相覆寫。
 * 持股、股票、交易用「代號／holdingId」對應，程式裡由 Service 配對，不用 JPA 關聯，
 * 避免關閉 open-in-view 後在畫面上讀到未載入資料的錯誤（LazyInitializationException），查詢次數也固定。
 *
 * version 欄位（V5 已加）暫不對應：C3 設計「手機與電腦同時操作」時才加上 @Version（C2-5 決定）。
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
