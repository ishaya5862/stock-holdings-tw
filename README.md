# 持股記帳 Stock Holdings Ledger (stock-holdings-tw)

記下自己買了哪些台股，系統用官方收盤價算出「如果現在全部賣掉，淨賺或淨虧多少」（扣除手續費與證交稅）。

## 需要先安裝

| 軟體 | 版本 | 說明 |
|---|---|---|
| JDK | **17 以上**（17、21、25 皆可） | 執行 `java -version` 確認 |
| Docker Desktop | 任一近期版本 | 用來啟動 MySQL；**啟動程式前請先打開 Docker Desktop** |

不需要另外安裝 Maven（專案內附 Maven Wrapper），也不需要自己安裝或設定 MySQL。

## 啟動方式（一行指令）

在專案根目錄執行：

```bash
# Windows（命令提示字元或 PowerShell）
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

啟動時會**自動**依 `compose.yml` 啟動 MySQL 容器，並由 Flyway 建立資料表，不需要手動執行 `docker compose`。
第一次啟動要下載套件與 MySQL 映像檔，約需 1～3 分鐘。

看到 `Started StockHoldingsApplication` 後，打開瀏覽器：

**http://localhost:8080**

停止：在終端機按 `Ctrl + C`（MySQL 容器也會一併停止，資料保留）。

## 資料庫

- MySQL 8.4（Docker 容器），資料庫名稱 `stock_holdings`。
- 資料表由 Flyway 建立：`src/main/resources/db/migration/`。
- 資料存在 Docker volume，關掉程式、重開電腦資料都還在。要清空資料：`docker compose down -v`。
- 電腦這端的埠號由 Docker 自動挑選（避免和已安裝的 MySQL 或 Windows 保留埠衝突）。要用資料庫工具連線時，先查實際埠號：
  ```bash
  docker compose port mysql 3306
  ```
  帳號 `app`，密碼見下方「密碼設定」。

### 密碼設定

repo 裡**沒有真實密碼**，`compose.yml` 與 `application.properties` 中的 `devpass` 只是本機開發用的預設值。
正式環境請用環境變數覆蓋：

| 環境變數 | 預設值 | 說明 |
|---|---|---|
| `DB_PASSWORD` | `devpass` | MySQL 帳號 `app` 的密碼 |
| `DB_ROOT_PASSWORD` | `rootdevpass` | MySQL root 密碼（只用於 compose.yml） |
| `DB_HOST` / `DB_PORT` / `DB_USERNAME` | `localhost` / `3306` / `app` | 不透過 Docker Compose、改連自己的 MySQL 時使用（例如 `java -jar`） |

## 執行測試

```bash
# Windows
mvnw.cmd test

# macOS / Linux
./mvnw test
```

測試使用 H2 記憶體資料庫（MySQL 相容模式），**不需要開 Docker**，也不會連到證交所或櫃買中心。

## 功能

| 網址 | 說明 |
|---|---|
| http://localhost:8080 | 首頁（標題與目前台北時間） |
| http://localhost:8080/holdings | 持股清單：新增、修改、刪除 |

持股欄位規則：股票代號必填（同一代號只能一筆）、張數為 1～999 的整數（1 張 = 1,000 股）、買進價大於 0 且最多兩位小數。

## 官方行情

清單上的股票名稱與收盤價一律取自官方：

| 市場 | 來源（題目指定） |
|---|---|
| 上市 | 臺灣證券交易所 `https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20261101&stockNo=2330&response=json` |
| 上櫃 | 證券櫃檯買賣中心 `https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/01&response=json` |

- **新增持股**：只要輸入代號，系統同時查證交所與櫃買中心，自動判斷上市或上櫃，10 秒內一定有結果。
  - 兩邊都查不到 → 顯示「查無股票代號」，資料不儲存。
  - 官方連不上，無法確認代號是否存在 → 顯示「行情來源暫時無法連線，請稍後再試」，資料不儲存。
- **收盤價更新**：打開清單**不會**向官方查詢，只讀伺服器記憶體中的快取；快取由背景排程更新：
  - 伺服器啟動後立刻查一輪，之後每一輪結束隔 10 分鐘再查。
  - 只查需要更新的股票：還沒查過、上次查詢失敗，或「開盤日 13:30（台北時間）之後還沒拿到當天收盤價」的股票。
  - 每一次請求之間間隔 6 秒，避免短時間大量查詢被證交所阻擋；某個市場查詢失敗時，這一輪暫停查詢該市場。
  - 官方公布當天收盤價後，一小時內就會出現在清單上，不需要重開伺服器。
- **清單上收盤價那一格**：

  | 顯示 | 意思 |
  |---|---|
  | `2,330.00`（下方小字 `11/19`） | 收盤價與日期（官方已公布的最近一個交易日） |
  | 更新中… | 伺服器剛啟動，背景排程還沒輪到這一檔 |
  | 未取得 | 最近一次向官方查詢失敗（連不上、被阻擋）；整頁仍正常顯示 |
  | 查無此代號 | 上市、上櫃都查不到這個代號 |

  「更新中」「未取得」時，若曾經取得過收盤價，會用小字顯示「上次收盤價 X（日期）」。

### 更換官方網址（例如換成測試伺服器）

兩個網址的**主機部分**可以用環境變數換掉，路徑和參數不變。例如把兩個都換成 `http://localhost:9000`：

```bash
# Windows（命令提示字元 cmd）
set TWSE_BASE_URL=http://localhost:9000
set TPEX_BASE_URL=http://localhost:9000
mvnw.cmd spring-boot:run

# Windows（PowerShell）
$env:TWSE_BASE_URL="http://localhost:9000"
$env:TPEX_BASE_URL="http://localhost:9000"
.\mvnw.cmd spring-boot:run

# macOS / Linux
TWSE_BASE_URL=http://localhost:9000 TPEX_BASE_URL=http://localhost:9000 ./mvnw spring-boot:run
```

換掉後程式會改呼叫：
- `http://localhost:9000/rwd/zh/afterTrading/STOCK_DAY?date=...&stockNo=...&response=json`
- `http://localhost:9000/www/zh-tw/afterTrading/tradingStock?code=...&date=...&response=json`

也可以直接修改 `src/main/resources/application.properties` 的 `app.quote.twse-base-url`、`app.quote.tpex-base-url`。

> 收盤價快取放在記憶體，**不放資料庫**：換了網址重開伺服器後，一定會重新向新的網址查詢，不會沿用之前從官方查到的價格。

### 休市日判斷（輔助功能，非題目要求）

為了在假日不重複查詢，排程會參考證交所的「市場開休市日期」
（`https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?date=20260101&response=json`）。
這支 API 不是題目指定的，所以設計成**取不到也不影響主要功能**，依序嘗試：

1. 官方 API。網址**不會**跟著 `TWSE_BASE_URL` 一起改，預設仍是真正的證交所。
2. 程式內附的備援檔 `src/main/resources/holidays/年份.json`（官方原始資料，目前有 2026、2027 年）。
3. 以上都沒有 → 平日一律當成開盤日，排程照常更新。

| 環境變數 | 預設值 | 說明 |
|---|---|---|
| `HOLIDAY_BASE_URL` | `https://www.twse.com.tw` | 休市日 API 的主機 |
| `HOLIDAY_API_ENABLED` | `true` | 設成 `false` 時不查官方 API、不對外連線，只用內附檔。換成測試伺服器、不希望程式連到真正的證交所時可以關掉 |

颱風假等臨時休市不在官方清單中，遇到時排程照常查詢。

### 已知限制

- 題目指定的 API 一次只能查「一檔股票、一個月」。每次請求間隔 6 秒時，每一輪 10 分鐘內約可查 100 檔；持股數量更多時，更新一輪的時間會變長。
- 快取與排程在每一台伺服器各自運作；若部署多台伺服器，每台都會各自向官方查詢。
- 伺服器剛啟動時，背景排程要逐檔查詢（每檔間隔 6 秒），清單上部分股票會先顯示「更新中…」。

## 設計說明

- **時區**：正式機在海外、主機時區不是台北，程式內所有時間一律以 `Asia/Taipei` 判斷（設定在 `app.zone`），不依賴主機設定。
- **重複代號**：先由程式檢查，資料庫再以 UNIQUE 限制把關（處理兩人同時新增的情況）。代號會先去掉前後空白、轉成大寫再比對。
- **資料表變更**：一律新增新版本的 Flyway migration（例如 `V2__...sql`），不修改已執行過的版本。
- **刪除方式**：目前為實體刪除（直接從資料表刪除該筆）。有評估過保留刪除紀錄的做法，例如另建歷史表，刪除前先把那筆存一份；但題目沒有要求，考量考試時間，這次先不做，之後有需要再擴充。不採用「軟刪除」（加刪除標記、資料留在原表），是因為刪掉的代號仍留在資料表裡，會被「同一代號只能一筆」的 UNIQUE 限制擋住，無法重新新增。

## 專案結構

```
src/main/java/io/qiao/
├── config/       設定（時區 Clock 等）
├── controller/   接收網頁請求，決定回傳哪個畫面（含網頁的統一例外處理）
├── dto/          表單資料（HoldingForm）、清單顯示資料（HoldingView）
├── exception/    自訂例外（代號重複、找不到持股、查無股票、行情無法取得）
├── quote/        官方行情：證交所／櫃買中心查詢、判斷上市上櫃、快取、休市日
├── service/      商業邏輯（含背景排程 QuoteRefreshScheduler）
├── repository/   存取資料庫（Spring Data JPA）
└── entity/       對應資料表的類別
src/main/resources/
├── db/migration/ Flyway 建表 SQL
├── holidays/     休市日備援資料（官方原始回應）
├── templates/    Thymeleaf 頁面（fragments/ 為共用版型）
├── messages.properties  表單型別錯誤的中文訊息
├── static/       CSS、JS、圖片
└── application.properties
compose.yml       MySQL（Docker Compose）
```

## 完成進度

| 題目 | 狀態 |
|---|---|
| 第 1 題：專案骨架 | ✅ |
| 第 2 題：持股的新增、修改、刪除、查詢 | ✅ |
| 第 3 題：串接官方行情 | ✅ |
| 第 4 題：算盈虧，並寫單元測試 | ⬜ |
| 第 5 題：給手機 App 用的 API | ⬜ |

> v1.0.0 為 Part B 發下時的進度快照（依題目規定「不管做到哪裡都先打 v1.0.0」），當時第 3 題只完成查詢與解析；第 3 題其餘部分在 v1.0.0 之後完成。
