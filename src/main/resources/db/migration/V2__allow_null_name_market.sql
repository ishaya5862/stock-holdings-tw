-- 股票名稱、市場別要到「第 3 題：串接官方行情」才查得到，
-- 第 2 題新增持股時還沒有這兩個值，所以先改成允許 NULL。
-- 為什麼不直接改 V1？已執行過的 migration 一旦修改，Flyway 會因為檢查碼（checksum）不符而拒絕啟動；
-- 資料表結構有變動時，一律新增新版本的 migration。
ALTER TABLE holdings MODIFY name VARCHAR(50) NULL;
ALTER TABLE holdings MODIFY market VARCHAR(4) NULL;
