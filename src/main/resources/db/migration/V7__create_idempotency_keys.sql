-- 2.0（第 6 題 C7）：防止重送（冪等）。App 2.0 送出後 10 秒沒收到回應會自動重送同一個請求，
-- 每個寫入請求帶一個 Idempotency-Key，伺服器記住「這個 key 已經做過什麼」，重送時不再執行、回傳結果。
--
-- 和交易寫在「同一個資料庫交易」（C7-2 a）：交易成功才留下 key；交易失敗 key 也不會留下（重送時重新執行）。
-- 主鍵（idempotency_key）的唯一限制讓「同時到達的兩個相同請求」排隊：第二個等第一個完成，再拿第一個的結果。

CREATE TABLE idempotency_keys (
	idempotency_key VARCHAR(64)  NOT NULL,                -- App 帶的 key（1～64 英數字 - _，建議 UUID）
	request_hash    VARCHAR(64)  NOT NULL,               -- 請求指紋（方法＋網址＋解析後欄位的 SHA-256）；同 key 不同指紋 → 422
	method          VARCHAR(10)  NOT NULL,                -- POST／PUT／DELETE（查問題用；指紋已包含）
	path            VARCHAR(200) NOT NULL,
	trade_id        BIGINT       NULL,                    -- 這次寫入的交易；重送時用它讀出目前狀態（不設外鍵：交易之後可能被刪）
	holding_code    VARCHAR(10)  NULL,                    -- 這次寫入的持股代號
	holding_deleted BOOLEAN      NOT NULL DEFAULT FALSE,  -- 刪除最後一筆交易，持股也一起刪除
	response_status INT          NOT NULL,                -- 第一次的狀態碼（201／200），重送時照用
	created_at      DATETIME     NOT NULL,                -- 台北時間；超過 24 小時視為過期（每小時清除）
	PRIMARY KEY (idempotency_key)
);

-- 每小時清除過期的 key 時用
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
