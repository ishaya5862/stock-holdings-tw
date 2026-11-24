# 執行與設定

> 從 README 搬過來的詳細說明。快速開始見 [README](../README.md)。

## 資料庫

- MySQL 8.4（Docker 容器），資料庫名稱 `stock_holdings`。
- 資料表由 Flyway 建立：`src/main/resources/db/migration/`（V1～V7）。
  - `holdings`（持股）：代號、版本號、建立／修改時間——只放使用者會修改的欄位（1.0 的張數、買進價在 2.0 轉成交易紀錄，V6 刪除）。
  - `stocks`（股票）：代號、名稱、市場別、上次收盤價——只有官方行情（新增持股、背景排程）會寫入。
  - `trades`（交易紀錄，2.0）：買或賣、日期、股數、價格；股數與成本每次依交易紀錄重算。
  - `idempotency_keys`（防止重送，2.0）：App 2.0 寫入時帶的 `Idempotency-Key`，保存 24 小時。
  - `holdings.code` → `stocks.code`、`trades.holding_id` → `holdings.id` 外鍵。
- 資料存在 Docker volume，關掉程式、重開電腦資料都還在。要清空資料：`docker compose down -v`。
- 電腦這端的埠號由 Docker 自動挑選（避免和已安裝的 MySQL 或 Windows 保留埠衝突）。要用資料庫工具連線時，先查實際埠號：
  ```bash
  docker compose port mysql 3306
  ```
  帳號 `app`，密碼見下方「密碼設定」。

### 密碼設定

repo 裡**沒有真實密碼**，`docker-compose.yml` 與 `application.properties` 中的 `devpass` 只是本機開發用的預設值。
正式環境請用環境變數覆蓋：

| 環境變數 | 預設值 | 說明 |
|---|---|---|
| `DB_PASSWORD` | `devpass` | MySQL 帳號 `app` 的密碼 |
| `DB_ROOT_PASSWORD` | `rootdevpass` | MySQL root 密碼（只用於 docker-compose.yml） |
| `DB_HOST` / `DB_PORT` / `DB_USERNAME` | `localhost` / `3306` / `app` | 改連自己的 MySQL 時使用（要同時設 `SPRING_DOCKER_COMPOSE_ENABLED=false`，見下方「用 jar 執行」） |

## 用 jar 執行（不需要原始碼）

自己打包：`mvnw.cmd package`（PowerShell：`.\mvnw.cmd package`；macOS / Linux：`./mvnw package`），jar 會在 `target/stock-holdings-tw-2.0.0.jar`。[GitHub Releases](https://github.com/ishaya5862/stock-holdings-tw/releases) 有 1.1.0 打包好的 jar（1.0 版）。以下用 2.0.0 為例。

1. 安裝 JDK 17 以上，並**打開 Docker Desktop**。
2. 把 jar 和 `docker-compose.yml` 放在**同一個資料夾**，在那個資料夾執行：
   ```bash
   java -jar stock-holdings-tw-2.0.0.jar
   ```
   和原始碼版本一樣會自動啟動 MySQL（第一次要下載 MySQL 映像檔，較久）。看到 `Started StockHoldingsApplication` 後打開 http://localhost:8080 。
3. 停止：按 `Ctrl + C`（MySQL 會一併停止）。若是直接關掉視窗，MySQL 會繼續執行，請在同一個資料夾執行 `docker compose stop`。

注意：
- **一定要在 jar 所在的資料夾執行**；找不到 `docker-compose.yml` 時會顯示 `No Docker Compose file found` 並停止。
- 資料存在 Docker volume，名稱跟資料夾名稱有關：**換一個資料夾執行就是一個新的空資料庫**（舊資料還在原本的資料夾對應的 volume 裡）。
- 8080 被佔用時可以換埠號：`java -jar stock-holdings-tw-2.0.0.jar --server.port=8081`。

進階：改連自己的 MySQL（不使用 docker-compose.yml），資料庫要先建立 `stock_holdings` 與帳號：

```bash
# Windows（命令提示字元 cmd）
set SPRING_DOCKER_COMPOSE_ENABLED=false
set DB_HOST=localhost
set DB_PORT=3306
set DB_PASSWORD=你的密碼
java -jar stock-holdings-tw-2.0.0.jar

# Windows（PowerShell）
$env:SPRING_DOCKER_COMPOSE_ENABLED="false"; $env:DB_HOST="localhost"; $env:DB_PORT="3306"; $env:DB_PASSWORD="你的密碼"
java -jar stock-holdings-tw-2.0.0.jar

# macOS / Linux
SPRING_DOCKER_COMPOSE_ENABLED=false DB_HOST=localhost DB_PORT=3306 DB_PASSWORD=你的密碼 java -jar stock-holdings-tw-2.0.0.jar
```
