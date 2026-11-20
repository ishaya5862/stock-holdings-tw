-- 2.0（第 6 題 C4）：「擴充 → 遷移 → 收縮」的收縮步驟。
-- v1 API 與第一版網頁已改成從 trades 計算張數、買進價（C4），holdings 的 lots、buy_price 不再使用，刪除。
-- 刪除後，2.0 才能記零股與多筆買賣（舊欄位 lots 有 NOT NULL 與 CHECK 1～999，存不下零股）。
--
-- 和 V4 相同：MySQL 要先刪掉欄位上的 CHECK 限制，才允許刪欄位。
-- ⚠ 執行後，1.x 的程式無法再用這個資料庫啟動（Holding 對應的 lots、buy_price 不存在）；
--   回滾 = 還原升級前的備份 + 舊版程式（UPGRADE.md）。

ALTER TABLE holdings DROP CONSTRAINT ck_holdings_lots;
ALTER TABLE holdings DROP CONSTRAINT ck_holdings_buy_price;
ALTER TABLE holdings DROP COLUMN lots;
ALTER TABLE holdings DROP COLUMN buy_price;
