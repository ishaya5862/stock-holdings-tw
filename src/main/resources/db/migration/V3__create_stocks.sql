-- 股票資料表：股票名稱、市場別、最後一次取得的收盤價，屬於「股票」本身（一個代號一筆），不屬於某一筆持股。
--
-- 為什麼拆出來（修正「遺失更新」）：
--   原本這些欄位放在 holdings，背景排程寫回行情時，Hibernate 會把整筆持股（含張數、買進價）一起寫回，
--   和使用者修改同時發生時會互相覆寫。拆開後背景排程只寫 stocks、使用者只改 holdings，兩邊不會碰到同一列。
--   Part B（同一檔多次買進、賣出）也能共用這張表。
--
-- 這支 migration 只「建表＋複製資料＋加外鍵」，刪除 holdings 舊欄位放在 V4：
--   MySQL 的 DDL（建表、改欄位）無法回滾，分兩支確保「資料一定先複製好，才刪欄位」。
CREATE TABLE stocks (
	code             VARCHAR(10)    NOT NULL,
	name             VARCHAR(50)    NULL,        -- 第 2 題時期的舊資料可能還沒查到，允許空白，排程會補上
	market           VARCHAR(4)     NULL,        -- TWSE（上市）或 TPEX（上櫃）
	last_close_price DECIMAL(10, 2) NULL,        -- 最後一次成功取得的收盤價（顯示「上次收盤價」用）
	last_price_date  DATE           NULL,
	updated_at       DATETIME       NOT NULL,
	PRIMARY KEY (code),
	CONSTRAINT ck_stocks_market CHECK (market IN ('TWSE', 'TPEX'))
);

-- 把每一筆持股的股票資訊搬過來（同一個代號只會有一筆持股，所以不會重複）
INSERT INTO stocks (code, name, market, last_close_price, last_price_date, updated_at)
SELECT code, name, market, last_close_price, last_price_date, updated_at
FROM holdings;

-- 外鍵：持股的代號一定要在股票表裡（新增持股時，程式會先寫 stocks 再寫 holdings）
ALTER TABLE holdings ADD CONSTRAINT fk_holdings_stock FOREIGN KEY (code) REFERENCES stocks (code);
