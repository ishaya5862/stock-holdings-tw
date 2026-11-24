# 設計說明與專案結構

> 從 README 搬過來的詳細說明。快速開始見 [README](../README.md)。

## 設計說明

- **時區**：正式機在海外、主機時區不是台北，程式內所有時間一律以 `Asia/Taipei` 判斷（設定在 `app.zone`），不依賴主機設定。
- **重複代號**：先由程式檢查，資料庫再以 UNIQUE 限制把關（處理兩人同時新增的情況）。代號會先統一寫法（去掉所有空白、全形轉半形、轉大寫）再比對，網頁與 API 共用同一份規則（`validation/StockCodes`），所以「２３３０」「 2330 」不會被當成不同代號而重複新增。
- **網頁與 API 分開**：API 的請求／回應格式（`api/` 套件）和網頁表單各自獨立，共用同一套商業邏輯（`HoldingService`）與盈虧計算。之後 App 2.0 改格式時，v1 API 照舊運作。
- **資料表變更**：一律新增新版本的 Flyway migration（例如 `V2__...sql`），不修改已執行過的版本。
- **避免背景排程覆寫使用者的修改**：股票名稱、市場別、上次收盤價放在 `stocks`，使用者的資料放在 `holdings`、`trades`。背景排程只寫 `stocks`（而且只用「只改指定欄位」的 UPDATE），兩邊同時寫入也不會互相覆寫（有兩個交易交錯執行的測試 `HoldingConcurrentUpdateTest`）。
- **2.0 交易紀錄（第 6 題）**：每一次買進、賣出都記一筆交易（`trades`：買或賣、日期、股數、價格），股數、平均成本、已實現與未實現損益**不存**，每次依日期從頭重算（`calculation/PositionCalculator`，移動平均成本法），資料只有一份，補登過去的交易或修改舊交易都算得對。1.0 的資料在升級時（Flyway V5）自動轉成買進紀錄，舊欄位在 V6 刪除（先加新表、再遷移、最後刪舊欄位）。
- **手機與電腦同時操作（第 6 題）**：寫入交易前先鎖住那一檔持股（`SELECT … FOR UPDATE`），後到的請求會等前一個完成、再用最新的交易紀錄重算，所以兩邊同時賣出也不會賣超（有兩個執行緒同時賣出的測試 `TradeConcurrencyTest`）。等鎖最多 3 秒；修改、刪除一筆交易要帶版本號，兩邊同時改同一筆時後送出的回 409。2.0 的寫入用 READ COMMITTED 隔離等級：拿到持股鎖之後讀的一定是最新的交易紀錄，MySQL 也不會鎖索引間隙（避免不同請求互相死結）；讀取仍用 REPEATABLE READ 的一致快照。等鎖逾時或死結一律回 409「資料正在更新，請稍後再試」，不會變成 500。已在真 MySQL 實測：同一檔同時 10 筆賣出只成功 1 筆、3 檔同時各 6 筆買進全部成功、同一筆同時 6 筆修改只成功 1 筆（各 3 輪，log 沒有死結）。這些情境也寫成真 MySQL 的自動測試（Testcontainers，`src/test/java/io/qiao/mysql/`：同時賣出不賣超、不同檔與不同 key 同時寫入不死結、同一個 key 只記一筆、等鎖逾時回 409）。
- **App 10 秒沒回應自動重送（第 6 題）**：`/api/v2` 的買賣、修改、刪除一律要帶標頭 `Idempotency-Key`（每個新操作產生一個 UUID，重送時沿用同一個）。key 和交易在同一個資料庫交易寫入（`idempotency_keys` 表，Flyway V7）：同一個 key 再送來時不再執行，回傳那次的狀態碼與目前的資料，並加標頭 `Idempotent-Replayed: true`；兩個相同的請求同時到達時，後到的排隊等前一個完成後拿結果。第一次失敗（例如賣超）不會記住 key，重送時重新執行；同一個 key 送不同內容回 422。key 保存 24 小時（有兩個請求同時到達的測試 `IdempotencyConcurrencyTest`）。App 1.0（`/api/v1`）不需要帶。
- **刪除方式**：目前為實體刪除（直接從資料表刪除該筆）。有評估過保留刪除紀錄的做法，例如另建歷史表，刪除前先把那筆存一份；但題目沒有要求，考量考試時間，這次先不做，之後有需要再擴充。不採用「軟刪除」（加刪除標記、資料留在原表），是因為刪掉的代號仍留在資料表裡，會被「同一代號只能一筆」的 UNIQUE 限制擋住，無法重新新增。

## 專案結構

```
src/main/java/io/qiao/
├── api/          給手機 App 1.0 的 JSON API（/api/v1）：請求／回應格式、統一錯誤格式 {"error":{...}}
├── calculation/  盈虧計算（ProfitCalculator 單筆、PortfolioSummary 總計、2.0 PositionCalculator 依交易紀錄重算），不依賴資料庫與網路
├── config/       設定（時區 Clock 等）
├── controller/   接收網頁請求，決定回傳哪個畫面（含網頁的統一例外處理、數字格式 ViewFormats；2.0 新網頁 V2PageController、V2Formats）
├── dto/          表單資料（HoldingForm）、清單顯示資料（HoldingView、HoldingListView）
├── exception/    自訂例外（代號重複、找不到持股、查無股票、行情無法取得；2.0 的賣超、版本衝突、等鎖逾時、需要更新 App 等）
├── quote/        官方行情：證交所／櫃買中心查詢、判斷上市上櫃、快取、休市日
├── service/      商業邏輯（HoldingService 1.0 持股、TradeService 2.0 買賣、HoldingLock 同時操作的鎖、StockService 股票資訊、背景排程 QuoteRefreshScheduler）
├── validation/   共用驗證規則（股票代號格式 StockCodes，網頁與 API 共用）
├── repository/   存取資料庫（Spring Data JPA）
└── entity/       對應資料表的類別（Holding 持股、Trade 交易紀錄、Stock 股票）
src/main/resources/
├── db/migration/ Flyway 建表 SQL
├── templates/    Thymeleaf 頁面（fragments/ 為第一版共用版型；v2/ 為 2.0 新網頁：總覽、明細、買進、賣出、修改）
├── messages.properties  表單型別錯誤的中文訊息
├── static/       CSS、JS（js/holding-form.js：代號即時檢查與防止重複送出；js/holding-list.js：刪除確認；css/v2.css、js/v2-page.js、js/v2-form.js、js/v2-trade-delete.js：2.0 新網頁）
├── holidays/     休市日備援資料 2021～2027（官方原始回應）
└── application.properties
docker-compose.yml  MySQL（Docker Compose）
screenshots/        App 畫面截圖
```
