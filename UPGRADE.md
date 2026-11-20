# 從 1.0 升級到 2.0

上線方式：**停掉 1.0 的伺服器，用同一個資料庫啟動 2.0 的伺服器**。資料表的變更由 Flyway 在 2.0 第一次啟動時自動執行，不需要手動跑 SQL。

| 誰要看 | 哪幾節 |
|---|---|
| 負責升級的人 | [1. 升級前](#1-升級前)、[2. 升級步驟](#2-升級步驟)、[4. 回滾](#4-回滾) |
| 想知道資料怎麼轉換的人 | [3. 資料庫變動](#3-資料庫變動) |
| 維運、客服 | [5. 上線後要注意的事](#5-上線後要注意的事) |
| App 2.0 團隊 | `API.md` |

| 項目 | 1.0（v1.0.0、v1.1.0） | 2.0（v2.0.0） |
|---|---|---|
| 資料表版本（Flyway） | V1～V4 | **V5～V7**（自動執行） |
| 需要的環境 | Java 17 以上、MySQL 8.4（Docker） | 相同，**不需要新增任何設定** |
| App 1.0（`/api/v1`） | 可用 | **繼續可用**（規格不變） |
| App 2.0（`/api/v2`） | — | 新增 |

---

## 1. 升級前

1. **備份資料庫**（升級無法直接退回 1.0，回滾一定要用這份備份，見[第 4 節](#4-回滾)）：

   ```bash
   # 在專案資料夾（docker-compose.yml 所在處）執行；密碼是 docker-compose.yml 的 DB_PASSWORD（預設 devpass）
   docker compose exec mysql mysqldump -uapp -pdevpass --no-tablespaces stock_holdings > backup-before-v2.sql
   ```

   確認檔案裡有 `CREATE TABLE \`holdings\`` 和你的資料。

2. **確認目前是 1.0**（Flyway 最新版本是 4）：

   ```bash
   docker compose exec mysql mysql -uapp -pdevpass stock_holdings -e "SELECT version FROM flyway_schema_history ORDER BY installed_rank"
   ```

3. 選一個使用量少的時間升級。停機時間只有「停 1.0 → 啟動 2.0」這段（實測 Flyway 執行 V5～V7 不到 1 秒，啟動約 10 秒）。

## 2. 升級步驟

1. **停掉 1.0 的伺服器**（`Ctrl + C`，或停止 1.0 的 jar）。資料庫（Docker 的 MySQL）**不要停、不要刪**。
2. **取得 2.0**：`git fetch --tags` 後 `git checkout v2.0.0`（或使用 2.0 的 jar）。
3. **用同一個資料庫啟動 2.0**：照 README「啟動方式」，資料庫連線設定（`DB_HOST`、`DB_PORT`、`DB_PASSWORD`）和 1.0 相同。
4. **確認 log 出現**：

   ```text
   Migrating schema `stock_holdings` to version "5 - create trades"
   Migrating schema `stock_holdings` to version "6 - drop lots buy price from holdings"
   Migrating schema `stock_holdings` to version "7 - create idempotency keys"
   Successfully applied 3 migrations to schema `stock_holdings`, now at version v7
   Started StockHoldingsApplication
   ```

5. **驗證**（伺服器剛啟動時，行情要一檔一檔查，約幾十秒內收盤價會陸續出現；`quote_missing` 變成 0 後再比對）：

   | 檢查 | 方法 | 預期 |
   |---|---|---|
   | App 1.0 的資料不變 | 升級前後各執行一次 `curl http://localhost:8080/api/v1/holdings`、`/api/v1/summary` 並比對 | 每一筆都相同。**唯一的例外**：債券 ETF（例如 00679B）的盈虧會變多，因為修正了證交稅（第 7 題回報二，見 `ISSUES.md`） |
   | 2.0 的資料 | `curl http://localhost:8080/api/v2/holdings` | 每一檔都有 `shares`（= 1.0 的張數 × 1,000）、`average_cost`、`total_cost` |
   | 2.0 網頁 | 打開 `http://localhost:8080/v2` | 持股總覽正常；明細裡 1.0 轉入的買進標示「推估」 |
   | App 1.0 | App 連上伺服器 | 清單與 1.0 時相同（截圖：`screenshots/v2-app1-list.png`） |

   **實際演練結果**（2026-11-21，1.0 的資料庫 6 檔持股）：
   - V5～V7 共 0.37 秒。
   - `/api/v1/holdings` 有 5 檔逐字元相同；00679B 的盈虧從 −11,867 變成 −11,789（少扣了 78 元證交稅，78,450 × 0.1% = 78）。
   - `/api/v1/summary` 的成本、市值不變，盈虧多 78 元。

## 3. 資料庫變動

依「**先新增、再搬資料、最後刪除舊欄位**」的順序，每一步都有測試。

| 版本 | 變動 | 說明 |
|---|---|---|
| **V5** | 新增 `trades`（交易紀錄）；`holdings` 加 `version` | 每一筆 1.0 的持股轉成**一筆買進**：股數 = 張數 × 1,000、價格 = 買進價、**日期 = 當初新增的日期**，並標記 `date_estimated = true`（1.0 沒有記錄買進日期，見 [5.2](#52-10-轉入的買進日期是推估的)）。`holdings.id` 不變，App 1.0 記住的 id 照樣能用 |
| **V6** | 刪除 `holdings.lots`、`holdings.buy_price` | 2.0 的股數、成本都由交易紀錄計算，不再存在 `holdings`。**這一步之後 1.x 程式無法再啟動**（見第 4 節） |
| **V7** | 新增 `idempotency_keys` | 防止 App 2.0 重送時重複記錄（`Idempotency-Key`），保存 24 小時，每小時自動清除過期的 |

| 資料表 | 1.0 | 2.0 |
|---|---|---|
| `holdings` | 代號、張數、買進價、時間 | 代號、時間、版本號（一檔一列） |
| `trades` | — | 每一次買進、賣出：日期、股數、價格、是否推估、版本號 |
| `stocks` | 名稱、市場、上次收盤價 | 不變 |
| `idempotency_keys` | — | 防止重送用 |

## 4. 回滾

2.0 的 V6 刪除了 1.x 使用的欄位，**不能直接把伺服器換回 1.x**：1.x 啟動時檢查資料表會失敗，不會把資料寫壞。

要回到 1.0：

1. 停掉 2.0 的伺服器。
2. 還原升級前的備份：

   ```bash
   docker compose exec -T mysql mysql -uapp -pdevpass stock_holdings < backup-before-v2.sql
   ```

3. 啟動 1.x（`git checkout v1.1.0` 或 1.1.0 的 jar）。

> 注意：**升級後在 2.0 新增、修改的資料會一起消失**（回到備份當時的狀態）。所以升級後請先完成第 2 節的驗證，確認沒問題再開放使用。

## 5. 上線後要注意的事

### 5.1 App 1.0 使用者會遇到的情況（客服說明用）

App 1.0 半年後預估還有三成使用者，`/api/v1` 照舊可用，規格不變。但 1.0 只認得「張數＋買進價」，遇到 2.0 才有的資料時：

| 情況 | App 1.0 看到的 | 給使用者的說明 |
|---|---|---|
| 持股有零股（例如 1,500 股） | 張數只顯示整張（**1 張**），零股捨去；名稱後面加註，例如「台積電【請更新 App 2.0：含零股 500 股】」。**盈虧、市值用全部股數計算**（金額正確） | 請更新到 App 2.0 看完整股數 |
| 只剩零股（例如 300 股） | 張數顯示 **0**，名稱加註「含零股 300 股」 | 同上 |
| 在 2.0 多次買進或賣出過的持股 | 買進價顯示平均買進價；**不能修改、刪除**（回 409 `UPGRADE_REQUIRED`，App 會顯示「請更新 App 2.0」的訊息） | 1.0 只能表達一筆買進，改了會毀掉 2.0 的買賣紀錄，請用 App 2.0 或網頁 `/v2` 修改 |
| 在 2.0 全部賣光的持股 | **不顯示**（張數 0 在 1.0 不合法） | 已實現損益請到 App 2.0 查看 |
| 賣光後用 App 1.0 重新新增同一檔 | 新增成功（沿用原本的 id），但**新增後馬上不能修改、刪除**（這檔有買賣紀錄） | 打錯張數時請用 App 2.0 或網頁 `/v2` 修改 |
| 修改、刪除時剛好另一個裝置在寫入同一檔 | 409 `HOLDING_BUSY`「資料正在更新，請稍後再試」 | 稍後再試 |
| 金額 | 只到「元」：每一筆先四捨五入，總計用捨入後的數字加總（清單加起來 = 總計），可能和 2.0 的精確金額差幾元 | 以 2.0 為準 |
| 某一檔交易資料異常（正常操作不會發生） | 那一檔不顯示，其他照常 | 請用 App 2.0 或網頁修正那一檔的交易 |

- v1 的 `PUT`、`DELETE` 新增了 409 `UPGRADE_REQUIRED`、409 `HOLDING_BUSY` 兩種錯誤（1.0 規格只列了 400、404）。App 1.0 對任何錯誤都會顯示 `message`，所以使用者看得到原因。
- v1 的回應加上 `Deprecation`、`Link` 標頭（預告未來會停止 v1）。App 1.0 使用者減少後，再評估停止 v1 的時間（之後加 `Sunset` 標頭預告日期）。

### 5.2 1.0 轉入的買進日期是推估的

1.0 沒有記錄買進日期，升級時用「當初新增的日期」代替並標示「推估」。

- 2.0 網頁的明細會標示「推估」，修改頁會提醒改成實際日期；App 2.0 可以用 `date_estimated` 提醒使用者。
- 使用者補登「比推估日期更早」的賣出時會被當成賣超擋下，錯誤訊息會說明原因並附上那筆買進（`details.estimated_buys`），改了買進日期就能記錄。

### 5.3 設定

| 環境變數 | 預設 | 什麼時候要改 |
|---|---|---|
| `BOND_ETF_TAX_EXEMPT_UNTIL` | `2026-12-31` | 債券 ETF 停徵證交稅的期限。延長到 2036 年的修正案（2026-11-11 立法院初審）**三讀公布後改成 `2036-12-31` 並重新啟動** |
| `HOLIDAY_API_ENABLED`、`HOLIDAY_BASE_URL` | `true`、證交所 | 同 1.0。每年證交所公布隔年休市日後，建議把資料放進 `src/main/resources/holidays/`（見該資料夾的 README） |
| `TWSE_BASE_URL`、`TPEX_BASE_URL` | 證交所、櫃買中心 | 同 1.0 |

其他（等鎖上限 3 秒、2.0 寫入使用 READ COMMITTED 隔離等級）都已寫在程式設定裡，不需要調整。

### 5.4 要觀察的 log

| log | 等級 | 意思與處理 |
|---|---|---|
| `持股資料異常，無法計算：代號 …、持股 id …、原因：…` | ERROR | 某一檔的交易時間線不合理（例如有人直接改了資料庫）。只有那一檔標示異常，其他照常；請使用者在明細修正，或檢查資料庫 |
| `寫入後重新計算失敗（寫入已提交）` | ERROR | 正常不會發生；發生時請檢查那一檔的交易 |
| `等鎖逾時` | WARN | 同一檔同時被寫入、排隊超過 3 秒。偶爾出現是正常的；**經常出現**代表使用量變大，要評估調整 |
| `已清除 N 個過期的 Idempotency-Key` | INFO | 每小時清除一次，正常 |

### 5.5 已知限制

升級後仍存在的限制（例如無法自動處理股票分割、只支援上市與上櫃的股票）整理在 README「已知限制」。
