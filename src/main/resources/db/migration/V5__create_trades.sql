-- 2.0（第 6 題 C2）：新增交易紀錄表 trades，把 1.0 的每筆持股轉成一筆買進紀錄。
--
-- 資料表設計 N1（完全正規化）：trades 只存原始事實（買或賣、日期、股數、價格），
-- 股數、平均成本、已實現／未實現損益每次依交易重算（PositionCalculator），不存衍生資料。
--
-- 做法「擴充 → 遷移 → 收縮」（expand-migrate-contract）：
--   V5（這支）只「加」不「刪」：holdings 的 lots、buy_price 先保留，等 v1 改成讀 trades 後（C4）才在 V6 刪除。
--   這樣每一步都能單獨上線、出問題好追查。
--
-- 注意：MySQL 的 DDL（建表、改欄位）無法回滾，升級前一定要先備份資料庫（UPGRADE.md）。

CREATE TABLE trades (
	id             BIGINT         NOT NULL AUTO_INCREMENT,
	holding_id     BIGINT         NOT NULL,                 -- 屬於哪一筆持股（holdings.id，App 1.0 用的 id 不變）
	side           VARCHAR(4)     NOT NULL,                 -- BUY（買進）或 SELL（賣出）
	trade_date     DATE           NOT NULL,                 -- 交易日期（台灣日期）
	date_estimated BOOLEAN        NOT NULL DEFAULT FALSE,   -- 日期是推估的（1.0 轉入、App 1.0 新增時沒有日期）
	shares         BIGINT         NOT NULL,                 -- 股數（BIGINT 對應 Java long，和計算核心一致）
	price          DECIMAL(10, 2) NOT NULL,                 -- 每股價格（和 1.0 的 buy_price 相同精度）
	version        BIGINT         NOT NULL DEFAULT 0,       -- 版本號：C3 設計同時修改的處理時才在程式對應
	created_at     DATETIME       NOT NULL,
	updated_at     DATETIME       NOT NULL,
	PRIMARY KEY (id),
	-- 外鍵不設 ON DELETE CASCADE（C2-2 決定）：刪持股時由程式明確先刪交易，避免誤刪就默默清掉交易歷史
	CONSTRAINT fk_trades_holding FOREIGN KEY (holding_id) REFERENCES holdings (id),
	CONSTRAINT ck_trades_side CHECK (side IN ('BUY', 'SELL')),
	CONSTRAINT ck_trades_shares CHECK (shares > 0),
	CONSTRAINT ck_trades_price CHECK (price > 0),
	-- 最早日期（C2-6：程式檢查＋資料庫兩層）；「不能是未來」做不到資料庫層（CHECK 不能用今天，且資料庫主機時區不一定是台北）
	CONSTRAINT ck_trades_trade_date CHECK (trade_date >= '1990-01-01')
);

-- 重算順序：持股 → 交易日期 → id（同一天依輸入順序），用索引就不用另外排序
CREATE INDEX idx_trades_holding_order ON trades (holding_id, trade_date, id);

-- 1.0 的每筆持股 → 一筆買進
--   shares = 張數 × 1,000；price = 買進價
--   trade_date = 建立時間的日期（1.0 沒有記錄買進日期；created_at 由程式用台北時區寫入，取日期不會差一天）
--   date_estimated = TRUE（D3：畫面與 API 標示「推估」，請使用者確認）
INSERT INTO trades (holding_id, side, trade_date, date_estimated, shares, price, version, created_at, updated_at)
SELECT id, 'BUY', CAST(created_at AS DATE), TRUE, lots * 1000, buy_price, 0, created_at, updated_at
FROM holdings
ORDER BY id;

-- 持股的版本號：手機與電腦同時操作時偵測衝突（C3 才在程式對應 @Version）
ALTER TABLE holdings ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
