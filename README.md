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

## 資料庫

- MySQL 8.4（Docker 容器），資料庫名稱 `stock_holdings`。
- 資料表由 Flyway 建立：`src/main/resources/db/migration/`（V1～V4）。
  - `holdings`（持股）：代號、張數、買進價、建立／修改時間——只放使用者會修改的欄位。
  - `stocks`（股票）：代號、名稱、市場別、上次收盤價——只有官方行情（新增持股、背景排程）會寫入。
  - `holdings.code` → `stocks.code` 外鍵。
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

[GitHub Releases](https://github.com/ishaya5862/stock-holdings-tw/releases) 提供打包好的 jar（例：`stock-holdings-tw-1.1.0.jar`）。也可以自己打包：`mvnw.cmd package`（PowerShell：`.\mvnw.cmd package`；macOS / Linux：`./mvnw package`），jar 會在 `target/`。

1. 安裝 JDK 17 以上，並**打開 Docker Desktop**。
2. 把 jar 和 `docker-compose.yml` 放在**同一個資料夾**，在那個資料夾執行：
   ```bash
   java -jar stock-holdings-tw-1.1.0.jar
   ```
   和原始碼版本一樣會自動啟動 MySQL（第一次要下載 MySQL 映像檔，較久）。看到 `Started StockHoldingsApplication` 後打開 http://localhost:8080 。
3. 停止：按 `Ctrl + C`（MySQL 會一併停止）。若是直接關掉視窗，MySQL 會繼續執行，請在同一個資料夾執行 `docker compose stop`。

注意：
- **一定要在 jar 所在的資料夾執行**；找不到 `docker-compose.yml` 時會顯示 `No Docker Compose file found` 並停止。
- 資料存在 Docker volume，名稱跟資料夾名稱有關：**換一個資料夾執行就是一個新的空資料庫**（舊資料還在原本的資料夾對應的 volume 裡）。
- 8080 被佔用時可以換埠號：`java -jar stock-holdings-tw-1.1.0.jar --server.port=8081`。

進階：改連自己的 MySQL（不使用 docker-compose.yml），資料庫要先建立 `stock_holdings` 與帳號：

```bash
# Windows（命令提示字元 cmd）
set SPRING_DOCKER_COMPOSE_ENABLED=false
set DB_HOST=localhost
set DB_PORT=3306
set DB_PASSWORD=你的密碼
java -jar stock-holdings-tw-1.1.0.jar

# Windows（PowerShell）
$env:SPRING_DOCKER_COMPOSE_ENABLED="false"; $env:DB_HOST="localhost"; $env:DB_PORT="3306"; $env:DB_PASSWORD="你的密碼"
java -jar stock-holdings-tw-1.1.0.jar

# macOS / Linux
SPRING_DOCKER_COMPOSE_ENABLED=false DB_HOST=localhost DB_PORT=3306 DB_PASSWORD=你的密碼 java -jar stock-holdings-tw-1.1.0.jar
```

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

API 的測試在 `src/test/java/io/qiao/api/`（逐欄位檢查型別與 null、每種錯誤碼、App 1.0 實際會送出的格式如 `"1.5"`、`"abc"`、`null`）。

## 功能

| 網址 | 說明 |
|---|---|
| http://localhost:8080 | 首頁（標題與目前台北時間） |
| http://localhost:8080/holdings | 持股清單：新增、修改、刪除 |
| http://localhost:8080/api/v1/... | 給手機 App 1.0 用的 JSON API（見下方「手機 App 1.0 API」） |

持股欄位規則：

| 欄位 | 規則 |
|---|---|
| 股票代號 | 必填；同一代號只能一筆；**4～6 位數字，最後可加 1 個英文字母**（例：2330、0050、006208、00679B、2881A）。全形數字與英文自動轉半形、所有空白（含全形空白）自動去除、英文自動轉大寫（網頁與 API 相同）；修改時不能改代號 |
| 張數 | 1～999 的整數（1 張 = 1,000 股） |
| 買進價 | 大於 0，最多兩位小數；上限 99,999,999.99（資料表欄位 DECIMAL(10,2)） |

> 股票代號不限制成「只能數字」，因為債券 ETF（00679B）、反向／槓桿 ETF（00632R、00631L）、特別股（2881A）的代號都含有英文字母。
> 新增頁輸入代號時會**即時檢查格式**（不用等按下新增）；後端一定會再檢查一次，前端檢查只是提示。
> 按下「新增」「儲存修改」後，按鈕變成「送出中…」、填寫區蓋上遮罩，跳轉前不能重複送出或修改內容；清單頁的刪除也一樣（先確認、再顯示「刪除中…」）。
> 修改頁的代號不放在輸入框，改成上方「股票資訊」區塊顯示代號、名稱、市場，避免誤以為可以修改。
> 新增頁、修改頁會依張數與買進價**即時試算「預估買進成本」**（買進金額＋買進手續費，例：1 張 × 1000 元 → 1,000,000 ＋ 1,425 ＝ 1,001,425 元），收合時只顯示合計（例：「▸ 預估買進成本 1,001,425 元（看明細）」），點開看買進金額與手續費；只在畫面顯示、不送出也不存檔。手續費率由後端（`ProfitCalculator`）提供給前端，規則只有一份；前端用整數（BigInt）計算，避免小數誤差。

## 官方行情

清單上的股票名稱與收盤價一律取自官方：

| 市場 | 來源（題目指定） |
|---|---|
| 上市 | 臺灣證券交易所 `https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20261101&stockNo=2330&response=json` |
| 上櫃 | 證券櫃檯買賣中心 `https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/01&response=json` |

- **新增持股**：只要輸入代號，系統同時查證交所與櫃買中心，自動判斷上市或上櫃，10 秒內一定有結果。
  - 兩邊近兩個月都沒有成交資料 → 顯示「查無股票代號 …（請確認代號，或該股可能已停止交易、下市）」，資料不儲存。
  - 官方連不上，無法確認代號是否存在 → 顯示「行情來源暫時無法連線，請稍後再試」，資料不儲存。
  - 「查無」和「連不上」分開處理：官方連不上時不會說代號不存在。
- **收盤價更新**：打開清單**不會**向官方查詢，只讀伺服器記憶體中的快取；快取由背景排程更新：
  - 伺服器啟動後立刻查一輪，之後每一輪結束隔 10 分鐘再查。
  - 只查需要更新的股票：還沒查過、上次查詢失敗，或「開盤日 13:30（台北時間）之後還沒拿到當天收盤價」的股票。
  - 每一次請求之間間隔 6 秒，避免短時間大量查詢被證交所阻擋；同一個市場**連續 2 檔**查詢失敗才暫停這一輪的該市場（只有單一檔有問題時不影響其他股票）。
  - 查詢順序：還沒查過 → 正常要更新 → 近期無成交 → 上次失敗；上次失敗的排在最後，某一檔一直失敗也擋不到其他股票。
  - 16:00 之後同一檔連續 3 次查到的仍不是當天收盤價（例如颱風假、當天停止交易），當天就不再查這一檔，避免一直查到半夜。
  - 官方公布當天收盤價後，一小時內就會出現在清單上，不需要重開伺服器（持股約 500 檔以內，見「已知限制與未來迭代」）。
- **已存在的持股近兩個月都沒有成交資料時**（股票可能長期停止交易、轉市場或下市）：

  | 檢查 | 頻率 | 做什麼 |
  |---|---|---|
  | 完整檢查 | 每檔每天一次 | 1. 改查另一個市場：查到 → 判斷為「上櫃轉上市（或反過來）」，自動更新市場別<br>2. 原市場往前多查 4 個月：找到最後一次成交 → 顯示「最後收盤價 X（日期）」<br>3. 都沒有 → 顯示「近期無成交資料（可能停止交易或已下市）」 |
  | 快速重查 | 開盤日 13:30 之後、每 40 分鐘 | 只查原市場近兩個月（半夜、週末、休市日查了也不會有新資料，所以不查）；一有成交資料就恢復正常顯示（40 ＋ 排程間隔 10 分鐘 ＋ 查詢時間 ≤ 60 分鐘，符合「公布後一小時內」） |

  任何一步官方連不上時不下結論，顯示「未取得」，下一輪再試。
- **清單上收盤價那一格**：

  | 顯示 | 意思 |
  |---|---|
  | `2,330.00`（下方小字 `11/19`） | 收盤價與日期（官方已公布的最近一個交易日） |
  | 更新中… | 伺服器剛啟動，背景排程還沒輪到這一檔 |
  | 未取得 | 最近一次向官方查詢失敗（連不上、被阻擋）；整頁仍正常顯示 |
  | 近期無成交資料（可能停止交易或已下市） | 上市、上櫃近期都沒有成交；找得到最後一次成交時，一併顯示「最後收盤價 X（年/月/日）」 |

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

### 已知限制與未來迭代

以下是目前版本的限制。「未來迭代方向」是初步想法，**具體做法仍在思考討論中、尚未實作**，之後的版本再逐項評估。

| 限制 | 原因 | 目前的處理 | 未來迭代方向（待討論） |
|---|---|---|---|
| **持股超過約 500 檔（不同代號）時，無法保證公布後一小時內全部更新** | 題目指定的 API 一次只能查「一檔、一個月」，每次請求間隔 6 秒避免被證交所阻擋：10 分鐘（排程間隔）＋ 檔數 × 6 秒 ≤ 60 分鐘 → 約 500 檔（月初每檔要查兩個月，約 250 檔）。台股上市約 1,100 檔一般股票，全部持有時一輪約 110 分鐘 | 同一代號不論幾人持有只查一次；已拿到當天收盤價、13:30 前、休市日都不查。以本服務的規模（數十檔）一輪只要幾分鐘 | 改用證交所 `STOCK_DAY_ALL`、櫃買中心 OpenAPI 每日收盤行情「一次查全部」（實測 1 次請求不到 1 秒涵蓋全市場），查不到再退回題目的 API。**這兩支的網址必須跟著 `TWSE_BASE_URL`／`TPEX_BASE_URL` 一起更換**，避免更換成測試伺服器時被真實價格蓋掉 |
| 新增持股時只查近兩個月 | 新增要在 10 秒內有結果 | 停止交易超過兩個月的股票無法新增，訊息會說明「可能已停止交易、下市」 | 是否開放「確認後仍可新增」需再評估（題目要求代號不存在時不儲存） |
| 停止交易超過約五個月的股票，找不到最後收盤價 | 往前只多查 4 個月，控制請求數 | 顯示「近期無成交資料（可能停止交易或已下市）」 | 增加 `app.quote.lookback-months` |
| 無法明確判斷「已下市」 | 題目指定的 API 只提供成交資料，沒有上市狀態 | 用中性文字「近期無成交資料（可能停止交易或已下市）」，不武斷說代號不存在 | 參考官方證券清單（ISIN）。但考官的測試伺服器不會有這份資料，會和測試資料互相矛盾，所以只能「查無時補充說明」、不能推翻題目 API 的結果 |
| 颱風假等臨時休市 | 不在官方休市日清單中 | 16:00 之後每一檔連續 3 次查到的都不是當天收盤價，當天就不再查（不會一直查到半夜）；顯示最近一個交易日的收盤價 | 串接官方的臨時休市公告 |
| 內附的休市日資料只有 2026、2027 年 | 官方每年公布一次 | 之後年份會查官方 API；查不到時平日一律當成開盤 | 每年把新年度的資料放進 `src/main/resources/holidays/` |
| 部署多台伺服器時，每台各自查詢 | 快取與排程放在每台伺服器的記憶體 | 單台伺服器部署 | 改用共用快取（例如 Redis）＋ 只讓一台執行排程 |
| 伺服器剛啟動時，部分股票先顯示「更新中…」 | 啟動後的第一輪要逐檔查詢（每次間隔 6 秒） | 啟動後立刻開始第一輪；有上次收盤價時一併顯示 | 採用上面「一次查全部」後，第一輪可在幾秒內完成 |
| 手機與電腦同時操作同一檔持股時，後到的請求要排隊；等超過 3 秒會失敗 | 2.0 起寫入前先鎖住那一檔持股（悲觀鎖），才能保證「不能賣超」；等太久會讓 App（10 秒沒回應就重送）的請求越堆越多 | 正常情況鎖只佔幾毫秒；等超過 3 秒回 409「資料正在更新，請稍後再試」（實測 MySQL 3.25 秒回應）。同一筆交易在兩邊同時修改時，用版本號偵測，後送出的回 409，不會默默蓋掉（App 1.0 的修改沒有版本欄位，以鎖排隊、後送出的為準） | 依實際使用量調整等鎖上限（`spring.datasource.hikari.connection-init-sql`） |
| 沒有登入與使用者帳號，所有人看到同一份持股 | 題目與 App 1.0 API 都沒有使用者的概念 | 單一使用者使用 | 需要多人使用時再加帳號與權限 |
| 第 2 題時期新增、還沒查到市場別的舊持股，App 暫時看不到 | API 規格規定 `market` 只能是 `TWSE` 或 `TPEX`、不可為 null | 這幾筆在 API 不輸出（單筆查詢、修改、刪除回 404 `NOT_FOUND`），網頁照常顯示；背景排程查到市場別後就會出現在 App | — |

#### 2.0（第 6 題，製作中）的已知限制

2.0 改成「交易紀錄」記帳：用「股」記（可記零股）、同一檔可多次買進、可賣出一部分。以下是目前已完成部分的限制。

| 限制 | 原因 | 目前的處理 | 未來迭代方向（待討論） |
|---|---|---|---|
| **App 1.0 看 2.0 的資料不完整**：張數只顯示整張（零股捨去）、看不到已實現損益、看不到已全部賣出的股票 | App 1.0 已發布、規格不會再改：`lots` 只能是整數，也沒有已實現損益的欄位 | 盈虧、市值一律用真實股數計算（金額正確）；這種持股的名稱後面加註原因，例如「台積電【請更新 App 2.0：含零股 500 股】」；第一版網頁也會標示 | 半年後 App 1.0 使用者減少再評估停止 v1（加上 `Sunset` 標頭預告） |
| **App 1.0（與第一版網頁）不能修改、刪除在 2.0 操作過的持股** | 1.0 只有「張數＋買進價」，修改或刪除會毀掉 2.0 的買賣紀錄 | 有零股、多次買進或賣出紀錄的持股不能修改；有多筆交易的不能刪除；回 409 `UPGRADE_REQUIRED`，訊息請使用者更新到 App 2.0 | — |
| 1.0 的金額只顯示到「元」，可能和 2.0 的精確金額差幾元 | 規格規定金額是整數；2.0 的零股、部分賣出會算到「分」 | 每筆先四捨五入，總計用捨入後的數字加總（清單加起來 = 總計）；第一版網頁出現這種持股時加註說明 | — |
| 1.0 轉入的持股沒有真正的買進日期 | 1.0 只記錄「新增的時間」 | 升級時用新增那天當買進日期，並標記「推估」；使用者在 2.0 修改日期後取消標記 | — |
| 同一天的多筆交易，依輸入的先後順序計算 | 只有日期、沒有成交時間 | 先依日期、同一天再依輸入順序；要調整同一天的順序，刪除後重新記錄 | 記錄成交時間 |
| 修改或刪除過去的交易，之後每次賣出的已實現損益都會跟著重算 | 2.0 一律依交易紀錄從頭重算（資料只存一份，補登、修改都算得對） | 改完會造成某一天賣超時，擋下並說明是哪一天、當時持有多少 | — |
| 伺服器不擋週末、休市日的交易日期 | 記帳記的是使用者自己的交易；歷史休市資料不完整，硬擋會誤擋正確的資料 | 只擋未來日期與 1990-01-01 以前；「這天可能不是交易日，確定嗎？」由 2.0 網頁提醒（製作中） | — |
| 股票分割、減資等不會自動調整 | 尚未處理（例如 0050 於 2025 年 6 月一拆四） | 需使用者自行修改交易的股數與價格 | 第 7 題評估 |
| 升級 2.0 後無法直接改回 1.x 程式 | 2.0 的 migration（V6）刪除了 1.x 使用的欄位 | 升級前請先備份資料庫；要回到 1.x 時「還原備份＋舊版程式」 | — |

## 盈虧計算

盈虧的意思是「假設現在用收盤價全部賣出，淨賺多少」。程式：`calculation/ProfitCalculator`（單筆）、`calculation/PortfolioSummary`（總計）。

### 計算規則

| 項目 | 規則 |
|---|---|
| 手續費 | 成交金額 × 0.1425%，**無條件捨去到元**，每筆最低 20 元；買進、賣出各收一次 |
| 證交稅 | 只有賣出收。一般股票 0.3%，ETF（代號 00 開頭）0.1%；無條件捨去到元 |
| 盈虧 | 賣出金額 − 賣出手續費 − 證交稅 − 買進金額 − 買進手續費 |
| 收益率 | 盈虧 ÷（買進金額 ＋ 買進手續費）× 100，四捨五入到小數第二位（負數一樣四捨五入） |

- 金額一律用 `BigDecimal` 計算，避免 `double` 的誤差。
- 手續費是「先捨去、再和 20 元比」：14.25 → 14 → 未滿 20，收 20。
- 計算核心以「股數」為單位；1.0 版以張數呼叫時先乘 1,000，計算規則只有一份。

### 範例（單元測試的驗收資料）

| 例子 | 代號 | 張數 | 買進價 | 收盤價 | 買進手續費 | 賣出手續費 | 證交稅 | 盈虧 | 收益率 |
|---|---|---|---|---|---|---|---|---|---|
| 一般股票 | 2330 | 1 | 1000.00 | 1100.00 | 1,425 | 1,567 | 3,300 | 93,708 | 9.36% |
| ETF | 0050 | 2 | 150.00 | 145.50 | 427 | 414 | 291 | −10,132 | −3.37% |
| 手續費低於 20 元 | 2363 | 1 | 10.00 | 10.50 | 20 | 20 | 31 | 429 | 4.28% |

### 頁面最上方的總計

| 項目 | 規則 |
|---|---|
| 總成本 | 每筆（買進金額 ＋ 買進手續費）加總，**包含**取不到收盤價的持股 |
| 市值 | 每筆（收盤價 × 股數）加總，不扣費用；不含取不到收盤價的持股 |
| 總盈虧 | 每筆盈虧加總；不含取不到收盤價的持股 |
| 收益率 | 總盈虧 ÷ 總成本 × 100（重新計算，不是平均每筆的收益率） |

例子 1 ＋ 例子 2 的總計：總成本 1,301,852、市值 1,391,000、盈虧 83,576、收益率 6.42%（與 App API 規格的範例一致，也寫成單元測試）。

### 畫面顯示

- 清單每一筆顯示盈虧與收益率，頁面最上面顯示總計；台股慣例**紅色是賺、綠色是虧**，0 不上色。
- 取不到收盤價的持股：盈虧欄顯示「無法計算」，小字說明原因（尚未取得收盤價／近期無成交資料）。
- 沒有持股：總計顯示「目前沒有持股，新增後即可計算盈虧」。
- 全部都取不到收盤價：市值、盈虧、收益率顯示「尚未取得收盤價，暫時無法計算」，不顯示會被誤以為「不賺不賠」的 0。
- 部分取不到收盤價：照上面規則計算，並加註「N 筆未取得收盤價，未計入市值與盈虧」。

### 單元測試

測試**不會**連到證交所或櫃買中心（官方查詢在測試中都換成假的回應）。

| 測試類別 | 內容 |
|---|---|
| `ProfitCalculatorTest` | 上面三個例子（含每個中間值）、手續費 20 元邊界、無條件捨去、ETF 判斷、張數與股數結果相同 |
| `PortfolioSummaryTest` | 規格範例（例子 1 ＋ 例子 2）、收益率不是平均、部分／全部取不到收盤價、沒有持股 |
| `ViewFormatsTest`、`HoldingControllerTest` | 正負號、千分位、紅賺綠虧；清單與總計實際顯示的數字 |

只跑盈虧相關的測試：

```bash
# Windows（命令提示字元 cmd）
mvnw.cmd test -Dtest="ProfitCalculatorTest,PortfolioSummaryTest"

# Windows（PowerShell）
.\mvnw.cmd test "-Dtest=ProfitCalculatorTest,PortfolioSummaryTest"

# macOS / Linux
./mvnw test -Dtest="ProfitCalculatorTest,PortfolioSummaryTest"
```

## 手機 App 1.0 API

給手機 App 1.0 使用，回應一律是 JSON（UTF-8）。網址前綴 `/api/v1`，欄位名稱用小寫加底線（例：`buy_price`）。

### App 連線方式

伺服器照上面「啟動方式」啟動即可，不需要另外設定（預設接受所有網路介面的連線）。

| App 執行在 | App 裡填的伺服器網址 | 說明 |
|---|---|---|
| Android 模擬器（同一台電腦） | `http://10.0.2.2:8080` | `10.0.2.2` 是模擬器裡代表「這台電腦」的固定位址 |
| 實體手機 | `http://電腦的區網IP:8080`（例：`http://192.168.1.23:8080`） | 手機與電腦要連**同一個 Wi-Fi**；電腦的 IP 用 `ipconfig`（Windows）或 `ifconfig`（macOS）查。Windows 第一次啟動時防火牆若跳出詢問，請允許 Java 在「私人網路」通訊 |

> 實體手機連不上時，多半是 Windows 防火牆擋住 8080。可用系統管理員身分開 PowerShell 加一條規則：
> `New-NetFirewallRule -DisplayName "Stock Holdings 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private`
> （不需要時用 `Remove-NetFirewallRule -DisplayName "Stock Holdings 8080"` 移除）

### API 一覽

| 方法與路徑 | 成功 | 失敗 |
|---|---|---|
| `GET /api/v1/holdings` | 200，持股物件陣列（沒有持股時 `[]`） | — |
| `GET /api/v1/holdings/{id}` | 200，持股物件 | 404 `NOT_FOUND` |
| `POST /api/v1/holdings` | **201**，新建的持股物件（附 `Location` 標頭） | 400 `VALIDATION_ERROR`、404 `STOCK_NOT_FOUND`、409 `DUPLICATE_CODE`、503 `QUOTE_UNAVAILABLE` |
| `PUT /api/v1/holdings/{id}` | 200，修改後的持股物件 | 400 `VALIDATION_ERROR`、404 `NOT_FOUND` |
| `DELETE /api/v1/holdings/{id}` | **204**，沒有內容 | 404 `NOT_FOUND` |
| `GET /api/v1/summary` | 200，總計物件 | — |

- `POST` 的內容：`{"code":"2330","lots":1,"buy_price":1000}`；和網頁一樣會向證交所、櫃買中心確認代號，**最多約 10 秒**。
- `PUT` 的內容：`{"lots":2,"buy_price":980.5}`；代號不能改，內容帶了 `code` 也會忽略。

### 持股物件

| 欄位 | 型別 | 可否 null | 說明 |
|---|---|---|---|
| `id` | 整數 | 否 | |
| `code` | 字串 | 否 | 股票代號 |
| `name` | 字串 | 否 | 官方名稱（舊資料還沒查到名稱時用代號代替） |
| `market` | 字串 | 否 | `TWSE`（上市）或 `TPEX`（上櫃） |
| `lots` | 整數 | 否 | 張數 |
| `buy_price` | 數字 | 否 | 買進價（最多兩位小數，不會輸出成 `1E+3`） |
| `current_price` | 數字 | 可 | 收盤價；取不到時為 null |
| `price_date` | 字串 | 可 | 收盤價日期 `YYYY-MM-DD`（西元年）；取不到時為 null |
| `pnl` | 整數 | 可 | 盈虧（元，已扣手續費與證交稅）；取不到收盤價時為 null |
| `return_pct` | 數字 | 可 | 收益率（%，小數兩位）；取不到收盤價時為 null |

可以為 null 的欄位一定會出現在 JSON 裡（值為 `null`），不會被省略。

### 總計物件

| 欄位 | 型別 | 可否 null | 說明 |
|---|---|---|---|
| `total_cost` | 整數 | 否 | 總成本，**包含**取不到收盤價的持股 |
| `market_value` | 整數 | 否 | 市值，不含取不到收盤價的持股 |
| `pnl` | 整數 | 否 | 總盈虧，不含取不到收盤價的持股 |
| `return_pct` | 數字 | 可 | 總盈虧 ÷ 總成本 × 100；**沒有持股時為 null**（避免除以 0）；全部取不到收盤價時為 `0.00` |
| `quote_missing` | 整數 | 否 | 取不到收盤價的持股筆數 |

計算規則與網頁相同，見「盈虧計算」。

### 錯誤格式

所有錯誤（包括 JSON 格式錯誤、`/holdings/abc`、不存在的路徑、HTTP 方法不對、伺服器內部錯誤）一律回：

```json
{"error":{"code":"DUPLICATE_CODE","message":"2330 已在持股中"}}
```

`message` 是給使用者看的中文，不包含程式內部細節。

| `code` | HTTP 狀態碼 | 什麼時候 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 欄位缺少、型別不對、超出範圍；JSON 格式錯誤 |
| `NOT_FOUND` | 404 | 找不到這筆持股（包括 id 不是數字）、不存在的 API 路徑 |
| `STOCK_NOT_FOUND` | 404 | 股票代號不存在（官方查無），或代號格式不可能存在（例如 `ABC`，不浪費官方查詢） |
| `DUPLICATE_CODE` | 409 | 這個代號已在持股中 |
| `QUOTE_UNAVAILABLE` | 503 | 官方行情連不上，無法確認代號（資料不儲存，請稍後再試） |
| `METHOD_NOT_ALLOWED` | 405 | 這個路徑不支援這個 HTTP 方法 |
| `INTERNAL_ERROR` | 500 | 伺服器發生未預期的錯誤 |

### 輸入檢查（嚴格，不自動轉換型別）

App 1.0 送出前不會先檢查輸入，所以由伺服器把關：

| 送來的值 | 結果 |
|---|---|
| `lots`：`1.5`、`"1"`、`"abc"`、`null`、`0`、`1000` | 400，「張數必須是 1～999 的整數」（不會把 1.5 截成 1、不會把 `"1"` 轉成 1） |
| `buy_price`：`"abc"`、`null`、`0`、`10.123` | 400，「買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字」 |
| `code`：沒填、`""`、只有空白 | 400，「請輸入股票代號」 |
| `code`：數字 `2330`（不是字串） | 400（否則 `0050` 會被當成數字 50） |
| `code`：`"２３３０"`、`" 2330 "`、`"00679b"` | 和網頁一樣自動轉成 `2330`、`00679B` |
| 好幾個欄位同時錯（例如 `{}`） | 400，訊息一次列出，依「代號 → 張數 → 買進價」用「；」連接 |

### 用 curl 測試

Windows 10 以上內建 curl。下面的 `1` 請換成實際的持股 id（`GET /api/v1/holdings` 查得到）。

```bash
# 查詢（各種終端機都一樣；PowerShell 請把 curl 打成 curl.exe）
curl http://localhost:8080/api/v1/holdings
curl http://localhost:8080/api/v1/summary

# Windows（命令提示字元 cmd）、macOS / Linux
curl -i -X POST http://localhost:8080/api/v1/holdings -H "Content-Type: application/json" -d "{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}"
curl -i -X PUT http://localhost:8080/api/v1/holdings/1 -H "Content-Type: application/json" -d "{\"lots\":2,\"buy_price\":980.5}"
curl -i -X DELETE http://localhost:8080/api/v1/holdings/1
```

```powershell
# Windows（PowerShell）：要打 curl.exe（curl 在 PowerShell 是另一個指令的別名），JSON 外面用單引號
curl.exe -i -X POST http://localhost:8080/api/v1/holdings -H "Content-Type: application/json" -d '{\"code\":\"2330\",\"lots\":1,\"buy_price\":1000}'
curl.exe -i -X PUT http://localhost:8080/api/v1/holdings/1 -H "Content-Type: application/json" -d '{\"lots\":2,\"buy_price\":980.5}'
curl.exe -i -X DELETE http://localhost:8080/api/v1/holdings/1
```

### App 畫面截圖

App 1.0 連上本服務的持股清單：[`screenshots/v1-app1-list.png`](screenshots/v1-app1-list.png)

## 設計說明

- **時區**：正式機在海外、主機時區不是台北，程式內所有時間一律以 `Asia/Taipei` 判斷（設定在 `app.zone`），不依賴主機設定。
- **重複代號**：先由程式檢查，資料庫再以 UNIQUE 限制把關（處理兩人同時新增的情況）。代號會先統一寫法（去掉所有空白、全形轉半形、轉大寫）再比對，網頁與 API 共用同一份規則（`validation/StockCodes`），所以「２３３０」「 2330 」不會被當成不同代號而重複新增。
- **網頁與 API 分開**：API 的請求／回應格式（`api/` 套件）和網頁表單各自獨立，共用同一套商業邏輯（`HoldingService`）與盈虧計算。之後 App 2.0 改格式時，v1 API 照舊運作。
- **資料表變更**：一律新增新版本的 Flyway migration（例如 `V2__...sql`），不修改已執行過的版本。
- **避免背景排程覆寫使用者的修改**：股票名稱、市場別、上次收盤價放在 `stocks`，使用者的資料放在 `holdings`、`trades`。背景排程只寫 `stocks`（而且只用「只改指定欄位」的 UPDATE），兩邊同時寫入也不會互相覆寫（有兩個交易交錯執行的測試 `HoldingConcurrentUpdateTest`）。
- **2.0 交易紀錄（第 6 題）**：每一次買進、賣出都記一筆交易（`trades`：買或賣、日期、股數、價格），股數、平均成本、已實現與未實現損益**不存**，每次依日期從頭重算（`calculation/PositionCalculator`，移動平均成本法），資料只有一份，補登過去的交易或修改舊交易都算得對。1.0 的資料在升級時（Flyway V5）自動轉成買進紀錄，舊欄位在 V6 刪除（先加新表、再遷移、最後刪舊欄位）。
- **手機與電腦同時操作（第 6 題）**：寫入交易前先鎖住那一檔持股（`SELECT … FOR UPDATE`），後到的請求會等前一個完成、再用最新的交易紀錄重算，所以兩邊同時賣出也不會賣超（有兩個執行緒同時賣出的測試 `TradeConcurrencyTest`）。等鎖最多 3 秒；修改、刪除一筆交易要帶版本號，兩邊同時改同一筆時後送出的回 409。
- **刪除方式**：目前為實體刪除（直接從資料表刪除該筆）。有評估過保留刪除紀錄的做法，例如另建歷史表，刪除前先把那筆存一份；但題目沒有要求，考量考試時間，這次先不做，之後有需要再擴充。不採用「軟刪除」（加刪除標記、資料留在原表），是因為刪掉的代號仍留在資料表裡，會被「同一代號只能一筆」的 UNIQUE 限制擋住，無法重新新增。

## 專案結構

```
src/main/java/io/qiao/
├── api/          給手機 App 1.0 的 JSON API（/api/v1）：請求／回應格式、統一錯誤格式 {"error":{...}}
├── calculation/  盈虧計算（ProfitCalculator 單筆、PortfolioSummary 總計、2.0 PositionCalculator 依交易紀錄重算），不依賴資料庫與網路
├── config/       設定（時區 Clock 等）
├── controller/   接收網頁請求，決定回傳哪個畫面（含網頁的統一例外處理、數字格式 ViewFormats）
├── dto/          表單資料（HoldingForm）、清單顯示資料（HoldingView、HoldingListView）
├── exception/    自訂例外（代號重複、找不到持股、查無股票、行情無法取得；2.0 的賣超、版本衝突、等鎖逾時、需要更新 App 等）
├── quote/        官方行情：證交所／櫃買中心查詢、判斷上市上櫃、快取、休市日
├── service/      商業邏輯（HoldingService 1.0 持股、TradeService 2.0 買賣、HoldingLock 同時操作的鎖、StockService 股票資訊、背景排程 QuoteRefreshScheduler）
├── validation/   共用驗證規則（股票代號格式 StockCodes，網頁與 API 共用）
├── repository/   存取資料庫（Spring Data JPA）
└── entity/       對應資料表的類別（Holding 持股、Trade 交易紀錄、Stock 股票）
src/main/resources/
├── db/migration/ Flyway 建表 SQL
├── holidays/     休市日備援資料（官方原始回應）
├── templates/    Thymeleaf 頁面（fragments/ 為共用版型）
├── messages.properties  表單型別錯誤的中文訊息
├── static/       CSS、JS（js/holding-form.js：代號即時檢查與防止重複送出；js/holding-list.js：刪除確認）
└── application.properties
docker-compose.yml  MySQL（Docker Compose）
screenshots/        App 畫面截圖
```

## 完成進度

| 題目 | 狀態 |
|---|---|
| 第 1 題：專案骨架 | ✅ |
| 第 2 題：持股的新增、修改、刪除、查詢 | ✅ |
| 第 3 題：串接官方行情 | ✅ |
| 第 4 題：算盈虧，並寫單元測試 | ✅ |
| 第 5 題：給手機 App 用的 API | ✅ |
| 第 6 題：2.0 版（零股、多次買進、部分賣出） | 🔶 製作中：計算核心、交易紀錄資料表與 1.0 資料轉換、1.0 介面改由交易紀錄計算、交易服務（含同時操作）、2.0 讀取與寫入 API（`/api/v2`）已完成；防止重送（冪等）與新網頁製作中 |

> v1.0.0 為 Part B 發下時的進度快照（依題目規定「不管做到哪裡都先打 v1.0.0」），當時第 3 題只完成查詢與解析。
> 以下都在 v1.0.0 之後完成：第 3 題其餘部分、第 3 題修正（股票資訊拆到 `stocks` 表，避免背景排程覆寫使用者的修改）、第 4 題、第 5 題，以及第 2 題的修正（代號全形轉半形、防止重複送出）。
