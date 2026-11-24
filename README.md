# 持股記帳 Stock Holdings Ledger (stock-holdings-tw)

記下自己買了哪些台股，系統用官方收盤價算出「如果現在全部賣掉，淨賺或淨虧多少」（扣除手續費與證交稅）。

目前版本是 **2.0**（v2.0.0）：用「股」記帳（可記零股）、同一檔分多次買進、賣出一部分；App 1.0 照舊可用。

> 這是一份面試上機考的專案（2026-11-20 考試），交卷後持續優化中。考試時的過程與回顧見 [REVIEW.md](REVIEW.md)。

> **使用前提**：設計給**單一使用者**、部署在**單一伺服器**上使用。
> - 沒有登入與帳號：**建議不要開放到網際網路**。能連到這個網址的人都能看到，也能新增、修改、刪除所有持股與交易紀錄（App 連線用區網即可）。
> - 行情快取與排程放在伺服器的記憶體：開多台伺服器時，每台會各自查詢官方。
>
> 詳見[已知限制](docs/limitations.md#5-使用與部署)。

## 需要先安裝

| 軟體 | 版本 | 說明 |
|---|---|---|
| JDK | **17 以上**（17、21、25 皆可） | 執行 `java -version` 確認；[用 Docker 啟動](#用-docker-啟動不需要安裝-jdk)時不需要 |
| Docker Desktop | 任一近期版本 | 用來啟動 MySQL；**啟動程式前請先打開 Docker Desktop** |

不需要另外安裝 Maven（專案內附 Maven Wrapper），也不需要自己安裝或設定 MySQL。

## 啟動方式（一行指令）

在專案根目錄執行：

```bash
# Windows（命令提示字元 cmd）
mvnw.cmd spring-boot:run

# Windows（PowerShell）：要加 .\，PowerShell 不會直接執行目前資料夾裡的檔案
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

> macOS / Linux 若出現 `Permission denied`，改用：`sh mvnw spring-boot:run`

啟動時會**自動**依 `docker-compose.yml` 啟動 MySQL 容器，並由 Flyway 建立資料表，不需要手動執行 `docker compose`。
第一次啟動要下載套件與 MySQL 映像檔，約需 1～3 分鐘。

看到 `Started StockHoldingsApplication` 後，打開瀏覽器：

**http://localhost:8080**

停止：在終端機按 `Ctrl + C`（MySQL 容器也會一併停止，資料保留）。

用 jar 執行、改連自己的 MySQL、密碼設定，見 [docs/setup.md](docs/setup.md)。

## 用 Docker 啟動（不需要安裝 JDK）

電腦只要有 Docker Desktop，在專案根目錄執行（各種終端機都一樣）：

```bash
docker compose --profile app up --build
```

- 會在 Docker 裡用 Java 17 編譯程式，再和 MySQL 一起啟動。第一次要下載映像檔與套件，約需 1～5 分鐘（看網路速度）；之後有快取，會快很多。
- 看到 `Started StockHoldingsApplication` 後，一樣打開 **http://localhost:8080** 。
- 停止：按 `Ctrl + C`。在背景執行：加 `-d`（`docker compose --profile app up --build -d`），停止時執行 `docker compose --profile app stop`。
- 8080 被占用時換埠號：`APP_PORT=8081 docker compose --profile app up --build`（Windows PowerShell：先執行 `$env:APP_PORT="8081"`）。
- 更換官方網址的環境變數（見下方）一樣有效。
- 和上面的 `mvnw spring-boot:run` 用的是**同一個資料庫**（同一份資料）；兩種方式不要同時啟動。

## 網址

| 網址 | 說明 |
|---|---|
| http://localhost:8080 | 首頁；主要按鈕前往 2.0 網頁 |
| http://localhost:8080/v2 | **2.0 網頁**：持股總覽、交易明細、買進、賣出、修改 |
| http://localhost:8080/holdings | 第一版持股清單（以「張」記） |
| http://localhost:8080/api/v1/... | 手機 App 1.0 用的 API，規格見 [docs/api-v1.md](docs/api-v1.md) |
| http://localhost:8080/api/v2/... | 手機 App 2.0 用的 API，規格見 [API.md](API.md) |

各頁面的功能與盈虧的算法見 [docs/features.md](docs/features.md)。

## 更換官方網址（例如換成測試用的伺服器）

股票名稱與收盤價取自臺灣證券交易所、證券櫃檯買賣中心。兩個網址的**主機部分**可以用環境變數換掉，路徑和參數不變：

| 環境變數 | 預設值 | 說明 |
|---|---|---|
| `TWSE_BASE_URL` | `https://www.twse.com.tw` | 上市股票（證交所） |
| `TPEX_BASE_URL` | `https://www.tpex.org.tw` | 上櫃股票（櫃買中心） |
| `HOLIDAY_API_ENABLED` | `true` | 設成 `false` 時不向證交所查休市日、不對外連線，只用程式內附的資料 |

```bash
# macOS / Linux（Windows 的寫法見 docs/quote.md）
TWSE_BASE_URL=http://localhost:9000 TPEX_BASE_URL=http://localhost:9000 ./mvnw spring-boot:run
```

Windows 的設定方式、實際呼叫的網址、收盤價多久更新一次，見 [docs/quote.md](docs/quote.md)。

## 執行測試

```bash
# Windows（命令提示字元 cmd）
mvnw.cmd test

# Windows（PowerShell）
.\mvnw.cmd test

# macOS / Linux（Permission denied 時改用 sh mvnw test）
./mvnw test
```

測試使用 H2 記憶體資料庫（MySQL 相容模式），**不需要開 Docker**，也不會連到證交所或櫃買中心。

**有 Docker 時會多跑一組「真的 MySQL」測試**（`src/test/java/io/qiao/mysql/`）：鎖、交易隔離、死結的行為 H2 測不出來，
這組測試用 [Testcontainers](https://testcontainers.com/) 自動開一個 `mysql:8.4` 容器（和 `docker-compose.yml` 同版本），測完自動刪除，
不會動到開發用的資料庫。沒有 Docker（或 Docker Desktop 沒開）時，這組測試會顯示為略過（Skipped），其他測試照常執行。
第一次執行要下載 MySQL 映像檔，會比較久。

各測試類別測了什麼、2.0 網頁的 JavaScript 測試怎麼跑，見 [docs/features.md](docs/features.md)。

## 文件

| 文件 | 內容 |
|---|---|
| [docs/setup.md](docs/setup.md) | 資料庫、密碼設定、用 jar 執行、改連自己的 MySQL |
| [docs/features.md](docs/features.md) | 功能（網址、2.0 網頁、欄位規則）、盈虧計算規則與範例、測試 |
| [docs/quote.md](docs/quote.md) | 官方行情：上市上櫃判斷、收盤價更新、更換官方網址、休市日判斷 |
| [docs/api-v1.md](docs/api-v1.md) | 手機 App 1.0 用的 API（`/api/v1`） |
| [API.md](API.md) | 手機 App 2.0 用的 API（`/api/v2`） |
| [docs/design.md](docs/design.md) | 設計說明（時區、同時操作、防止重送等）、專案結構 |
| [docs/limitations.md](docs/limitations.md) | 已知限制與之後可以怎麼做 |
| [UPGRADE.md](UPGRADE.md) | 從 1.0 升級到 2.0：步驟、資料庫變動、回滾、上線後注意 |
| [ISSUES.md](ISSUES.md) | 客服轉來的三則回報：原因、查證來源、處理方式 |
| [REVIEW.md](REVIEW.md) | 考試的回顧：遇到的問題與解法、怎麼用 AI、之後的改善 |
| [docs/exam-progress.md](docs/exam-progress.md) | 考試時各題的完成進度與 tag 說明 |
| [screenshots/](screenshots/) | App 1.0 連上 1.0（`v1-app1-list.png`）與升級後的 2.0（`v2-app1-list.png`）的清單畫面 |
