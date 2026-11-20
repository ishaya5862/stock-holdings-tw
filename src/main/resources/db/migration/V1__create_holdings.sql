-- 持股資料表
-- 名稱、市場別在「新增持股」查詢官方行情時取得並存下來：
--   官方網站連不上時，清單仍能顯示股票名稱。
-- last_close_price / last_price_date：最後一次成功取得的收盤價，
--   只用來在取不到行情時顯示「未取得（上次收盤價 X）」；
--   「目前收盤價」一律以本次啟動後向官方（或考官的測試伺服器）查到的結果為準。
CREATE TABLE holdings (
	id               BIGINT        NOT NULL AUTO_INCREMENT,
	code             VARCHAR(10)   NOT NULL,                 -- 股票代號用字串，保留開頭的 0（如 0050）
	name             VARCHAR(50)   NOT NULL,
	market           VARCHAR(4)    NOT NULL,                 -- TWSE（上市）或 TPEX（上櫃）
	lots             INT           NOT NULL,                 -- 張數，1 張 = 1,000 股
	buy_price        DECIMAL(10, 2) NOT NULL,                -- 金額用 DECIMAL，避免浮點數誤差
	last_close_price DECIMAL(10, 2) NULL,
	last_price_date  DATE          NULL,
	created_at       DATETIME      NOT NULL,
	updated_at       DATETIME      NOT NULL,
	PRIMARY KEY (id),
	-- 同一個股票代號只能有一筆：程式先檢查，資料庫再擋一次（兩人同時新增時的最後防線）
	CONSTRAINT uk_holdings_code UNIQUE (code),
	CONSTRAINT ck_holdings_lots CHECK (lots BETWEEN 1 AND 999),
	CONSTRAINT ck_holdings_market CHECK (market IN ('TWSE', 'TPEX')),
	CONSTRAINT ck_holdings_buy_price CHECK (buy_price > 0)
);
