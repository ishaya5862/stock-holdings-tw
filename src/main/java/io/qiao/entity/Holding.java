package io.qiao.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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
 * 手機與電腦同時操作（第 6 題 C3）：寫入交易前先用 SELECT … FOR UPDATE 鎖住這一列（悲觀鎖），同一檔的寫入一定排隊。
 * version（@Version）：這檔有任何交易異動都會更新 updatedAt，Hibernate 自動把版本 +1（C3-9），
 * 用戶端可以用它判斷「這檔資料變了沒」；因為一定先鎖再讀，不會發生版本衝突。
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

	/** 版本號（V5 加的欄位）：每次異動自動 +1 */
	@Version
	@Column(nullable = false)
	private Long version;

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

	public Long getVersion() {
		return version;
	}
}
