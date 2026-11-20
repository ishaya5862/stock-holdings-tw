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

## 設計說明

- **時區**：正式機在海外、主機時區不是台北，程式內所有時間一律以 `Asia/Taipei` 判斷（設定在 `app.zone`），不依賴主機設定。
- **重複代號**：先由程式檢查，資料庫再以 UNIQUE 限制把關（處理兩人同時新增的情況）。代號會先去掉前後空白、轉成大寫再比對。
- **資料表變更**：一律新增新版本的 Flyway migration（例如 `V2__...sql`），不修改已執行過的版本。

## 專案結構

```
src/main/java/io/qiao/
├── config/       設定（時區 Clock 等）
├── controller/   接收網頁請求，決定回傳哪個畫面（含網頁的統一例外處理）
├── dto/          表單資料（HoldingForm）
├── exception/    自訂例外（代號重複、找不到持股）
├── service/      商業邏輯
├── repository/   存取資料庫（Spring Data JPA）
└── entity/       對應資料表的類別
src/main/resources/
├── db/migration/ Flyway 建表 SQL
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
| 第 3 題：串接官方行情 | ⬜ |
| 第 4 題：算盈虧，並寫單元測試 | ⬜ |
| 第 5 題：給手機 App 用的 API | ⬜ |
