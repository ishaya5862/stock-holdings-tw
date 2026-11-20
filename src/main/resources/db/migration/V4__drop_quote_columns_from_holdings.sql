-- 股票名稱、市場別、上次收盤價已在 V3 搬到 stocks，從 holdings 刪掉，避免兩邊各存一份、日後誤用舊資料。
-- holdings 之後只留「使用者會修改」的欄位：代號、張數、買進價、建立／修改時間。
--
-- 注意：執行後，v1.0.0 那一版的程式不能再用這個資料庫啟動（它會檢查這些欄位）。升級前請先備份資料庫。

-- market 欄位有 CHECK 限制，MySQL 不允許直接刪掉被 CHECK 用到的欄位，要先刪限制
ALTER TABLE holdings DROP CONSTRAINT ck_holdings_market;

ALTER TABLE holdings DROP COLUMN name;
ALTER TABLE holdings DROP COLUMN market;
ALTER TABLE holdings DROP COLUMN last_close_price;
ALTER TABLE holdings DROP COLUMN last_price_date;
