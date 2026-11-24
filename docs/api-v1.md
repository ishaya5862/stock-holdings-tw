# 手機 App 1.0 API

> App 1.0（`/api/v1`）的規格，從 README 搬過來。App 2.0（`/api/v2`）見 [API.md](../API.md)。

給手機 App 1.0 使用，回應一律是 JSON（UTF-8）。網址前綴 `/api/v1`，欄位名稱用小寫加底線（例：`buy_price`）。

## App 連線方式

伺服器照 [README「啟動方式」](../README.md) 啟動即可，不需要另外設定（預設接受所有網路介面的連線）。

| App 執行在 | App 裡填的伺服器網址 | 說明 |
|---|---|---|
| Android 模擬器（同一台電腦） | `http://10.0.2.2:8080` | `10.0.2.2` 是模擬器裡代表「這台電腦」的固定位址 |
| 實體手機 | `http://電腦的區網IP:8080`（例：`http://192.168.1.23:8080`） | 手機與電腦要連**同一個 Wi-Fi**；電腦的 IP 用 `ipconfig`（Windows）或 `ifconfig`（macOS）查。Windows 第一次啟動時防火牆若跳出詢問，請允許 Java 在「私人網路」通訊 |

> 實體手機連不上時，多半是 Windows 防火牆擋住 8080。可用系統管理員身分開 PowerShell 加一條規則：
> `New-NetFirewallRule -DisplayName "Stock Holdings 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private`
> （不需要時用 `Remove-NetFirewallRule -DisplayName "Stock Holdings 8080"` 移除）

## API 一覽

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

## 持股物件

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

## 總計物件

| 欄位 | 型別 | 可否 null | 說明 |
|---|---|---|---|
| `total_cost` | 整數 | 否 | 總成本，**包含**取不到收盤價的持股 |
| `market_value` | 整數 | 否 | 市值，不含取不到收盤價的持股 |
| `pnl` | 整數 | 否 | 總盈虧，不含取不到收盤價的持股 |
| `return_pct` | 數字 | 可 | 總盈虧 ÷ 總成本 × 100；**沒有持股時為 null**（避免除以 0）；全部取不到收盤價時為 `0.00` |
| `quote_missing` | 整數 | 否 | 取不到收盤價的持股筆數 |

計算規則與網頁相同，見[盈虧計算](features.md#盈虧計算)。

## 錯誤格式

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

## 輸入檢查（嚴格，不自動轉換型別）

App 1.0 送出前不會先檢查輸入，所以由伺服器把關：

| 送來的值 | 結果 |
|---|---|
| `lots`：`1.5`、`"1"`、`"abc"`、`null`、`0`、`1000` | 400，「張數必須是 1～999 的整數」（不會把 1.5 截成 1、不會把 `"1"` 轉成 1） |
| `buy_price`：`"abc"`、`null`、`0`、`10.123` | 400，「買進價必須是大於 0、最多兩位小數、不超過 99,999,999.99 的數字」 |
| `code`：沒填、`""`、只有空白 | 400，「請輸入股票代號」 |
| `code`：數字 `2330`（不是字串） | 400（否則 `0050` 會被當成數字 50） |
| `code`：`"２３３０"`、`" 2330 "`、`"00679b"` | 和網頁一樣自動轉成 `2330`、`00679B` |
| 好幾個欄位同時錯（例如 `{}`） | 400，訊息一次列出，依「代號 → 張數 → 買進價」用「；」連接 |

## 用 curl 測試

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

## App 畫面截圖

App 1.0 連上本服務的持股清單：[`screenshots/v1-app1-list.png`](../screenshots/v1-app1-list.png)

升級到 2.0 後，App 1.0 照樣能用（第 8 題）：用 1.0 留下的資料庫啟動 2.0，再用 App 1.0 連上去的清單畫面：[`screenshots/v2-app1-list.png`](../screenshots/v2-app1-list.png)（升級步驟見 [UPGRADE.md](../UPGRADE.md)）
