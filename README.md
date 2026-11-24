# 持股記帳 Stock Holdings Ledger (stock-holdings-tw)

記下自己買了哪些台股，系統用官方收盤價算出「如果現在全部賣掉，淨賺或淨虧多少」（扣除手續費與證交稅）。

目前版本是 **2.0**（v2.0.0）：用「股」記帳（可記零股）、同一檔分多次買進、賣出一部分；App 1.0 照舊可用。

| 文件 | 內容 |
|---|---|
| README.md（本文件） | 安裝、啟動、執行測試 |
| [docs/setup.md](docs/setup.md) | 資料庫、密碼設定、用 jar 執行、改連自己的 MySQL |
| [docs/features.md](docs/features.md) | 功能（網址、2.0 網頁、欄位規則）、盈虧計算規則與範例、單元測試 |
| [docs/quote.md](docs/quote.md) | 官方行情：上市上櫃判斷、收盤價更新、更換官方網址、休市日判斷 |
| [docs/api-v1.md](docs/api-v1.md) | 手機 App 1.0 用的 API（`/api/v1`） |
| [API.md](API.md) | 手機 App 2.0 用的 API（`/api/v2`） |
| [docs/design.md](docs/design.md) | 設計說明（時區、同時操作、防止重送等）、專案結構 |
| [docs/limitations.md](docs/limitations.md) | 已知限制與之後可以怎麼做 |
| [UPGRADE.md](UPGRADE.md) | 從 1.0 升級到 2.0：步驟、資料庫變動、回滾、上線後注意 |
| [ISSUES.md](ISSUES.md) | 客服轉來的三則回報：原因、查證來源、處理方式 |
| [REVIEW.md](REVIEW.md) | 回顧：遇到的問題與解法、怎麼用 AI、之後的改善 |
| [docs/exam-progress.md](docs/exam-progress.md) | 考試時各題的完成進度與 tag 說明 |
| [screenshots/](screenshots/) | App 1.0 連上 1.0（`v1-app1-list.png`）與升級後的 2.0（`v2-app1-list.png`）的清單畫面 |

## 需要先安裝

| 軟體 | 版本 | 說明 |
|---|---|---|
| JDK | **17 以上**（17、21、25 皆可） | 執行 `java -version` 確認 |
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

