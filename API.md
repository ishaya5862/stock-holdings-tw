# 持股記帳 2.0 API 文件（給 App 2.0 團隊）

App 2.0 用這份文件串接 `/api/v2`。App 1.0 用的 `/api/v1` 照舊可用，規格見 [docs/api-v1.md](docs/api-v1.md)。

## 目錄

1. [概要](#1-概要)
2. [快速開始](#2-快速開始)
3. [共用規則](#3-共用規則)（金額格式、錯誤格式、**防止重送 Idempotency-Key**、同時操作）
4. [API 一覽](#4-api-一覽)
5. [各 API 說明](#5-各-api-說明)
6. [物件定義](#6-物件定義)
7. [錯誤碼總表](#7-錯誤碼總表)
8. [計算規則](#8-計算規則)
9. [和 v1 的差異](#9-和-v1-的差異)

---

## 1. 概要

| 項目 | 規則 |
|---|---|
| 網址 | `http://{伺服器}:8080/api/v2/...`（Android 模擬器連同一台電腦：`http://10.0.2.2:8080`） |
| 格式 | 請求、回應都是 JSON（UTF-8）；送內容時帶 `Content-Type: application/json` |
| 欄位名稱 | 小寫加底線（`trade_date`、`average_cost`），和 v1 相同 |
| 一檔持股怎麼識別 | **股票代號**，例如 `/api/v2/holdings/2330`。代號會自動統一寫法：全形轉半形、去空白、轉大寫（`２３３０`、` 00679b ` 都可以） |
| 一筆交易怎麼識別 | 交易 id，例如 `/api/v2/trades/15` |
| 股數 | JSON 整數，單位是**股**（1 張 = 1,000 股；零股直接寫 300） |
| 金額、價格、收益率 | **兩位小數的字串**，例如 `"20252.25"`（理由見 [3.1](#31-金額為什麼是字串)） |
| 日期 | 字串 `YYYY-MM-DD`，一律是**台北時間**的日期 |
| 建議的標頭 | 每個請求都帶 `X-Client: app2`：萬一誤呼叫 v1，伺服器會回 400 `USE_API_V2`，開發時就能發現 |
| 認證 | 沒有登入與帳號（和 v1 相同） |
| 回應時間 | 所有 API 都在 10 秒內回應（第一次買某檔時要向證交所／櫃買中心確認代號，最多約 9 秒） |

---

## 2. 快速開始

```text
1. 持股清單        GET    /api/v2/holdings
2. 總計            GET    /api/v2/summary
3. 某一檔的交易明細  GET    /api/v2/holdings/2330/trades
4. 買進            POST   /api/v2/holdings/2330/trades   標頭 Idempotency-Key: <新的 UUID>
                   {"side":"BUY","trade_date":"2026-03-02","shares":300,"price":"600.00"}
5. 10 秒沒回應 → 用「同一個」Idempotency-Key 再送一次（不會重複記錄）
6. 修改一筆交易    PUT    /api/v2/trades/15              內容帶讀取時拿到的 version
7. 刪除一筆交易    DELETE /api/v2/trades/15?version=0
```

---

## 3. 共用規則

### 3.1 金額為什麼是字串

2.0 的零股與部分賣出會算到「分」，最大金額約 10^16 元。JSON 數字在很多語言（包括 JavaScript、部分 Android JSON 函式庫）會被當成浮點數，超過約 9×10^15 就會失真，也會有 0.1 + 0.2 ≠ 0.3 的誤差。

- 回應的金額一律是兩位小數的字串，請用精確的小數型別解析（例如 Java／Kotlin 的 `BigDecimal`）。
- 請求的 `price` 也是字串，例如 `"600.50"`。
- 股數是整數，最大值在精確範圍內，維持 JSON 數字。

### 3.2 null 的規則

- 可以為 null 的欄位**一定會出現**，值是 `null`（不會整個欄位不見）。
- 錯誤物件的 `fields`、`details` 例外：沒有時不出現。

### 3.3 錯誤格式

所有錯誤（包括 JSON 格式錯誤、不存在的網址、HTTP 方法不對、伺服器錯誤）一律是：

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "股數必須是整數（例如 300、1500）",
    "fields": { "shares": "股數必須是整數（例如 300、1500）" }
  }
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `code` | 字串 | 錯誤碼（見[錯誤碼總表](#7-錯誤碼總表)），App 用它判斷要怎麼處理 |
| `message` | 字串 | 給使用者看的中文，可以直接顯示；不包含程式內部細節 |
| `fields` | 物件（可能不出現） | 欄位錯誤：欄位名稱 → 那個欄位的錯誤訊息。一次列出所有錯誤的欄位，方便顯示在各輸入框旁 |
| `details` | 物件（可能不出現） | 額外資訊，例如賣超時是哪一天、當時持有多少（見 `OVERSELL`） |

### 3.4 防止重送：Idempotency-Key（一定要看）

App 在 10 秒沒收到回應時會自動重送。為了讓「同一個操作」不論送幾次都**只記一筆**，所有寫入（POST、PUT、DELETE）都**必須**帶標頭：

```http
Idempotency-Key: 8e03978e-40d5-43e8-bc93-6894a57f9324
```

| 規則 | 說明 |
|---|---|
| 格式 | 1～64 個英數字、`-`、`_`，**建議用 UUID**。也接受外層加雙引號（`"8e03…"`） |
| 什麼時候產生新的 | 使用者每做一個**新的操作**（按一次「確認買進」）產生一個新的 key |
| 什麼時候沿用 | **逾時、斷線、收到 409 `IDEMPOTENCY_KEY_IN_PROGRESS`** → 用同一個 key、同樣的內容再送 |
| 成功後 | 換新的 key；下一個操作用新的 |
| 保存多久 | 伺服器保存 24 小時；超過 24 小時的同一個 key 會被當成新請求 |

伺服器的處理：

| 情況 | 回應 |
|---|---|
| 第一次送 | 照常執行 |
| **重送**（同一個 key、同樣的內容，前一次已經成功） | **不再執行**。回傳第一次的狀態碼（201 或 200），內容是這筆交易與持股的**目前**狀態，並加標頭 `Idempotent-Replayed: true`；201 時一樣附 `Location` |
| 前一次還在處理（兩個請求同時到達） | 後到的會等前一個完成，再拿到上一列的「重送」結果；等超過 3 秒回 409 `IDEMPOTENCY_KEY_IN_PROGRESS` → 稍後用同一個 key 再送 |
| 前一次**失敗**（例如 400、404、409 賣超） | 失敗不會被記住，用同一個 key 再送會**重新執行** |
| 同一個 key、**不同內容**（或用在不同的 API） | 422 `IDEMPOTENCY_KEY_REUSED`，不執行（避免第二筆被當成重送而默默消失）→ 使用者改了內容就要產生新的 key |
| 沒帶 key | 400 `IDEMPOTENCY_KEY_REQUIRED` |
| 格式不對 | 400 `IDEMPOTENCY_KEY_INVALID` |

「同樣的內容」的判斷：方法＋網址（代號統一寫法後）＋解析後的欄位（`"600"` 和 `"600.00"`、欄位順序不同、多了空白，都算同樣的內容）。

重送時，中間如果另一個裝置把那筆交易或持股刪了，對應的欄位會是 `null`（不會回錯誤，App 不會誤以為失敗而再送一次）。

### 3.5 手機和電腦同時操作

| 情況 | 回應 | App 建議的處理 |
|---|---|---|
| 同一檔正在被另一個請求寫入，等超過 3 秒 | 409 `HOLDING_BUSY` | 顯示 message，稍後再試（可以用同一個 key） |
| 修改、刪除時，這筆交易已被另一個裝置改過 | 409 `VERSION_CONFLICT` | 重新讀取交易明細，讓使用者看過最新內容再決定 |
| 賣出時，另一個裝置剛賣掉一部分 | 409 `OVERSELL`（附當時持有股數） | 重新讀取持股，顯示最新持有股數 |
| 這筆交易或這檔持股已被另一個裝置刪除 | 404 `TRADE_NOT_FOUND`／`NOT_HELD` | 回到清單並重新讀取 |

伺服器保證：同時賣出不會賣超；同時修改同一筆不會默默覆蓋（真 MySQL 實測同時 10 筆賣出只成功 1 筆）。

### 3.6 輸入規則（硬性，不合格回 400 並在 `fields` 標出欄位）

| 欄位 | 規則 |
|---|---|
| `side` | 字串 `"BUY"` 或 `"SELL"`（大寫） |
| `trade_date` | 字串 `YYYY-MM-DD`；**1990-01-01 起，不能晚於台北時間的今天**。週末、休市日可以存（App 可以自行提醒使用者確認） |
| `shares` | JSON 整數，1～99,999,999 股（不接受 `"300"`、`1.5`、`1e3`）。數量更大請分成多筆記錄 |
| `price` | 字串，大於 0、最多兩位小數、最大 `"99999999.99"`（例：`"600"`、`"600.5"`、`"600.50"`；不接受數字 `600`、`"1e3"`、`" 600"`） |
| 金額 | `shares × price` 最多 **100 億元**（防止多打幾個 0），超過時錯誤標在 `shares` |
| `version` | JSON 整數（修改時放在內容；刪除時放網址參數 `?version=`） |

---

## 4. API 一覽

| 方法 | 路徑 | 說明 | 成功 |
|---|---|---|---|
| GET | `/api/v2/holdings` | 持股清單（含已全部賣出的） | 200 持股物件陣列 |
| GET | `/api/v2/holdings/{code}` | 單一持股 | 200 持股物件 |
| GET | `/api/v2/holdings/{code}/trades` | 某一檔的交易明細 | 200 交易物件陣列 |
| GET | `/api/v2/summary` | 總計 | 200 總計物件 |
| POST | `/api/v2/holdings/{code}/trades` | 買進或賣出（需 Idempotency-Key） | 201 寫入結果物件 |
| PUT | `/api/v2/trades/{id}` | 修改一筆交易（需 Idempotency-Key） | 200 寫入結果物件 |
| DELETE | `/api/v2/trades/{id}?version=N` | 刪除一筆交易（需 Idempotency-Key） | 200 寫入結果物件 |

---

## 5. 各 API 說明

### 5.1 持股清單 `GET /api/v2/holdings`

- 依代號排序；沒有持股時回 `[]`。
- **包含已全部賣出（`shares` 為 0）的持股**，才看得到它的已實現損益；要不要顯示由 App 決定。
- 不會向官方查詢，只讀伺服器的資料與行情快取。

回應 200（持股物件陣列，欄位見 [6.1](#61-持股物件)）：

```json
[
  {
    "id": 7, "code": "2330", "name": "台積電", "market": "TWSE",
    "shares": 600, "average_cost": "613.37", "total_cost": "368022.75", "realized_pnl": "16706.75",
    "quote_status": "OK", "close_price": "650.00", "price_date": "2026-11-20",
    "last_close_price": "650.00", "last_price_date": "2026-11-20",
    "market_value": "390000.00", "unrealized_pnl": "20252.25", "return_pct": "5.50",
    "data_error": null, "version": 3
  }
]
```

### 5.2 單一持股 `GET /api/v2/holdings/{code}`

- 回應 200：一個持股物件。
- 404 `NOT_HELD`：沒有持有過這個代號（或最後一筆交易已刪除，持股也一起刪了）。

### 5.3 交易明細 `GET /api/v2/holdings/{code}/trades`

- 排列順序：**交易日期 → 輸入順序**（同一天依輸入的先後），也就是計算平均成本的順序。
- 每一筆附上伺服器重算的手續費、證交稅、已實現損益、交易後股數。
- 404 `NOT_HELD`：沒有持有過這個代號。

回應 200（交易物件陣列，欄位見 [6.2](#62-交易物件)）：

```json
[
  { "id": 13, "side": "BUY",  "trade_date": "2026-03-02", "date_estimated": false, "shares": 300,
    "price": "600.00", "amount": "180000.00", "fee": "256.00", "tax": null,
    "cost_of_sold": null, "realized_pnl": null, "shares_after": 300, "version": 0 },
  { "id": 14, "side": "BUY",  "trade_date": "2026-05-15", "date_estimated": false, "shares": 500,
    "price": "620.00", "amount": "310000.00", "fee": "441.00", "tax": null,
    "cost_of_sold": null, "realized_pnl": null, "shares_after": 800, "version": 0 },
  { "id": 15, "side": "SELL", "trade_date": "2026-08-20", "date_estimated": false, "shares": 200,
    "price": "700.00", "amount": "140000.00", "fee": "199.00", "tax": "420.00",
    "cost_of_sold": "122674.25", "realized_pnl": "16706.75", "shares_after": 600, "version": 0 }
]
```

### 5.4 總計 `GET /api/v2/summary`

回應 200（欄位見 [6.3](#63-總計物件)）：

```json
{
  "total_cost": "368022.75", "market_value": "390000.00", "unrealized_pnl": "20252.25",
  "return_pct": "5.50", "realized_pnl": "16706.75",
  "holding_count": 1, "quote_missing": 0, "data_error_count": 0
}
```

### 5.5 買進或賣出 `POST /api/v2/holdings/{code}/trades`

標頭：`Idempotency-Key`（必帶）、`Content-Type: application/json`

```json
{ "side": "BUY", "trade_date": "2026-03-02", "shares": 300, "price": "600.00" }
```

| 欄位 | 必填 | 說明 |
|---|---|---|
| `side` | 是 | `"BUY"` 或 `"SELL"` |
| `trade_date` | 是 | 交易日期 |
| `shares` | 是 | 股數 |
| `price` | 是 | 每股價格（字串） |

- **買進還沒持有的代號**：伺服器會向證交所、櫃買中心確認代號存在，自動判斷上市或上櫃（最多約 9 秒）。只支援上市、上櫃的股票。
  - 補登舊交易時（交易日期比上個月更早），近兩個月查不到會改查「交易日期那個月」，已下市的股票也能補登。
- **賣出**：只能賣目前持有的；會依日期整條重算，任何一天賣超都會擋下。

回應 201，標頭 `Location: /api/v2/trades/{新交易的 id}`，內容是寫入結果物件（[6.4](#64-寫入結果物件)）：

```json
{
  "trade":   { "id": 13, "side": "BUY", "trade_date": "2026-03-02", "date_estimated": false, "shares": 300,
               "price": "600.00", "amount": "180000.00", "fee": "256.00", "tax": null,
               "cost_of_sold": null, "realized_pnl": null, "shares_after": 300, "version": 0 },
  "holding": { "id": 7, "code": "2330", "name": "台積電", "market": "TWSE", "shares": 300, "...": "（持股物件）" },
  "holding_deleted": false
}
```

| 錯誤 | 什麼時候 |
|---|---|
| 400 `VALIDATION_ERROR` | 欄位缺少、型別或範圍不對（`fields` 標出欄位） |
| 400 `IDEMPOTENCY_KEY_REQUIRED`／`IDEMPOTENCY_KEY_INVALID` | 沒帶 key、格式不對 |
| 404 `STOCK_NOT_FOUND` | 買進：上市、上櫃都查不到這個代號（或代號格式不可能存在，例如 `ABC`） |
| 404 `NOT_HELD` | 賣出：沒有持有這個代號 |
| 409 `OVERSELL` | 賣出（或補登更早的賣出）會讓某一天賣超 |
| 409 `HOLDING_BUSY` | 同一檔正在被另一個請求寫入，等超過 3 秒 |
| 409 `IDEMPOTENCY_KEY_IN_PROGRESS` | 同一個 key 的前一個請求還在處理 |
| 422 `IDEMPOTENCY_KEY_REUSED` | 同一個 key 用在不同內容 |
| 503 `QUOTE_UNAVAILABLE` | 買進新代號時官方連不上，無法確認代號（資料不儲存，稍後再試） |

`OVERSELL` 的 `details`：

```json
{
  "error": {
    "code": "OVERSELL",
    "message": "2026-03-02 賣出 1000 股，但當時只持有 0 股。這檔有 1 筆買進的日期是推估的（1.0 沒有買進日期，用記帳日 2026-11-20 代替），如果實際買進日期比較早，請先修改那筆買進的日期",
    "details": {
      "trade_date": "2026-03-02",
      "held_before": 0,
      "estimated_buys": [ { "id": 2, "trade_date": "2026-11-20", "version": 0 } ]
    }
  }
}
```

| `details` 欄位 | 型別 | 說明 |
|---|---|---|
| `trade_date` | 字串 | 哪一天賣超 |
| `held_before` | 整數 | 那一天賣出前持有多少股 |
| `estimated_buys` | 陣列（可能不出現） | 日期是「推估」的買進（1.0 轉入的資料沒有買進日期）。App 可以引導使用者用 PUT 修改那筆的日期 |

### 5.6 修改一筆交易 `PUT /api/v2/trades/{id}`

標頭：`Idempotency-Key`（必帶）

```json
{ "trade_date": "2026-03-02", "shares": 500, "price": "600.00", "version": 0 }
```

| 欄位 | 必填 | 說明 |
|---|---|---|
| `trade_date`、`shares`、`price` | 是 | 修改後的值 |
| `version` | 是 | 讀取交易明細時拿到的版本號。和伺服器不同 → 409 `VERSION_CONFLICT`（另一個裝置已改過，不會覆蓋） |

- **買賣種類、代號不能改**：內容帶了 `side` 或 `code` 會回 400（要改請刪除後重新記錄）。
- 修改後依日期整條重算；會造成某一天賣超 → 409 `OVERSELL`。
- 改了日期，`date_estimated` 會變成 `false`。

回應 200：寫入結果物件（`trade` 是修改後的交易，`version` 加 1）。

錯誤：400、404 `TRADE_NOT_FOUND`、409 `VERSION_CONFLICT`／`OVERSELL`／`HOLDING_BUSY`／`IDEMPOTENCY_KEY_IN_PROGRESS`、422。

### 5.7 刪除一筆交易 `DELETE /api/v2/trades/{id}?version=N`

標頭：`Idempotency-Key`（必帶）

- 刪除後依日期整條重算；會造成之後某一天賣超 → 409 `OVERSELL`（不刪除）。
- **刪掉這檔的最後一筆交易時，持股也一起刪除**：回應 `holding` 為 `null`、`holding_deleted` 為 `true`。

回應 200：

```json
{ "trade": null, "holding": { "...": "（重算後的持股物件）" }, "holding_deleted": false }
```

錯誤：400（缺 `version` 或不是整數）、404 `TRADE_NOT_FOUND`、409 `VERSION_CONFLICT`／`OVERSELL`／`HOLDING_BUSY`／`IDEMPOTENCY_KEY_IN_PROGRESS`、422。

---

## 6. 物件定義

### 6.1 持股物件

| 欄位 | 型別 | 可為 null | 說明 |
|---|---|---|---|
| `id` | 整數 | 否 | 持股 id（和 v1 相同；刪掉最後一筆交易後再買會變，長期記住請用 `code`） |
| `code` | 字串 | 否 | 股票代號 |
| `name` | 字串 | 可 | 股票名稱（官方） |
| `market` | 字串 | 可 | `TWSE`（上市）或 `TPEX`（上櫃） |
| `shares` | 整數 | 可 | 目前持有股數；`0` = 已全部賣出；資料異常時為 null |
| `average_cost` | 字串 | 可 | 平均成本（含買進手續費）= `total_cost ÷ shares`，四捨五入到分；0 股或資料異常為 null |
| `total_cost` | 字串 | 可 | 持有成本（目前持有股票的成本，含買進手續費）；資料異常為 null |
| `realized_pnl` | 字串 | 可 | 已實現損益（歷次賣出加總）；資料異常為 null |
| `quote_status` | 字串 | 否 | 行情狀態：`OK`／`UPDATING`（伺服器剛啟動，還沒查到）／`UNAVAILABLE`（官方連不上）／`NO_RECENT_TRADE`（近期無成交，可能停止交易或下市） |
| `close_price` | 字串 | 可 | 最近收盤價 |
| `price_date` | 字串 | 可 | 收盤價的日期 |
| `last_close_price` | 字串 | 可 | 上次成功取得的收盤價（`quote_status` 不是 OK 時可以參考顯示） |
| `last_price_date` | 字串 | 可 | 上次收盤價的日期 |
| `market_value` | 字串 | 可 | 市值 = 收盤價 × 股數；`quote_status` 不是 OK、0 股或資料異常為 null |
| `unrealized_pnl` | 字串 | 可 | 未實現損益（假設用收盤價全部賣出，已扣賣出手續費、證交稅）；條件同 `market_value` |
| `return_pct` | 字串 | 可 | 未實現收益率（%）= `unrealized_pnl ÷ total_cost × 100`，四捨五入到小數第二位 |
| `data_error` | 字串 | 可 | 資料異常的原因（交易時間線不合理，正常操作不會發生）；正常時為 null。發生時其他持股照常，請引導使用者到交易明細修正 |
| `version` | 整數 | 否 | 持股版本號：這檔有任何交易異動就加 1，可以用來判斷資料是否變動 |

### 6.2 交易物件

| 欄位 | 型別 | 可為 null | 說明 |
|---|---|---|---|
| `id` | 整數 | 否 | 交易 id（修改、刪除用） |
| `side` | 字串 | 否 | `BUY` 或 `SELL` |
| `trade_date` | 字串 | 否 | 交易日期 |
| `date_estimated` | 布林 | 否 | `true` = 日期是推估的（1.0 轉入、App 1.0 新增時沒有日期，用記帳日代替），建議提醒使用者確認 |
| `shares` | 整數 | 否 | 股數 |
| `price` | 字串 | 否 | 每股價格 |
| `amount` | 字串 | 可 | 成交金額 = 價格 × 股數 |
| `fee` | 字串 | 可 | 手續費 |
| `tax` | 字串 | 可 | 證交稅（只有賣出有；買進為 null） |
| `cost_of_sold` | 字串 | 可 | 這次賣出分攤掉的成本（只有賣出有） |
| `realized_pnl` | 字串 | 可 | 這次賣出的已實現損益（只有賣出有） |
| `shares_after` | 整數 | 可 | 這筆交易之後的持有股數 |
| `version` | 整數 | 否 | 交易版本號：修改、刪除時要帶上 |

持股資料異常時，`amount`～`shares_after` 這些計算出來的欄位都是 null，但交易本身照樣列出，使用者才能修正錯的那一筆。

### 6.3 總計物件

| 欄位 | 型別 | 可為 null | 說明 |
|---|---|---|---|
| `total_cost` | 字串 | 否 | 持有中（股數 > 0）各檔的持有成本加總；取不到收盤價的也算進來 |
| `market_value` | 字串 | 否 | 有收盤價的持股市值加總 |
| `unrealized_pnl` | 字串 | 否 | 有收盤價的持股未實現損益加總 |
| `return_pct` | 字串 | 可 | `unrealized_pnl ÷ total_cost × 100`；沒有持有中的持股為 null |
| `realized_pnl` | 字串 | 否 | 全部持股（含已全部賣出）的已實現損益加總 |
| `holding_count` | 整數 | 否 | 持有中的檔數 |
| `quote_missing` | 整數 | 否 | 持有中但取不到收盤價的檔數（不算進市值與未實現，但算進成本） |
| `data_error_count` | 整數 | 否 | 資料異常的檔數（不算進任何金額） |

### 6.4 寫入結果物件

買進、賣出、修改、刪除成功後回傳，App 不用再查一次（少一次請求，就少一次逾時重送的機會）。

| 欄位 | 型別 | 可為 null | 說明 |
|---|---|---|---|
| `trade` | 交易物件 | 可 | 新增或修改後的交易；刪除時為 null |
| `holding` | 持股物件 | 可 | 重算後的持股；持股被刪除時為 null |
| `holding_deleted` | 布林 | 否 | `true` = 刪掉的是最後一筆交易，持股也一起刪除 |

---

## 7. 錯誤碼總表

| `code` | HTTP | 什麼時候 | App 建議的處理 |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | 欄位缺少、型別或範圍不對、JSON 格式錯誤 | 依 `fields` 在各輸入框顯示訊息 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 寫入沒帶 `Idempotency-Key` | 程式錯誤，補上標頭 |
| `IDEMPOTENCY_KEY_INVALID` | 400 | key 格式不對 | 程式錯誤，改用 UUID |
| `NOT_FOUND` | 404 | 不存在的 API 網址 | 程式錯誤 |
| `NOT_HELD` | 404 | 沒有持有這個代號 | 回清單並重新讀取 |
| `TRADE_NOT_FOUND` | 404 | 找不到這筆交易（可能已在另一個裝置刪除） | 重新讀取交易明細 |
| `STOCK_NOT_FOUND` | 404 | 買進新代號：上市、上櫃都查不到 | 請使用者確認代號 |
| `METHOD_NOT_ALLOWED` | 405 | HTTP 方法不對 | 程式錯誤 |
| `OVERSELL` | 409 | 某一天賣超（附 `details`） | 顯示 message；重新讀取持有股數；有 `estimated_buys` 時引導修改那筆買進的日期 |
| `VERSION_CONFLICT` | 409 | 版本號不符，另一個裝置已改過 | 重新讀取，讓使用者看過再決定 |
| `HOLDING_BUSY` | 409 | 同一檔正在被寫入，等超過 3 秒；或資料正在更新 | 稍後再試 |
| `IDEMPOTENCY_KEY_IN_PROGRESS` | 409 | 同一個 key 的前一個請求還在處理 | 稍後用**同一個 key** 再送 |
| `IDEMPOTENCY_KEY_REUSED` | 422 | 同一個 key 用在不同內容或不同 API | 產生新的 key（使用者改了內容） |
| `QUOTE_UNAVAILABLE` | 503 | 買進新代號時官方連不上 | 稍後再試 |
| `INTERNAL_ERROR` | 500 | 伺服器發生未預期的錯誤 | 稍後再試 |

---

## 8. 計算規則

計算都在伺服器（App 只負責顯示），每次都依交易紀錄從頭重算。

| 項目 | 規則 |
|---|---|
| 手續費 | 成交金額 × 0.1425%，**無條件捨去到元，每筆最低 20 元**；買進、賣出各收一次（零股一樣最低 20 元） |
| 證交稅 | 只有賣出收；一般股票 0.3%、ETF（代號 00 開頭）0.1%，無條件捨去到元。**債券 ETF**（代號第 6 碼是 B 或 C，例如 `00679B`）在停徵期間（2017-01-01～2026-12-31，依賣出日期）**不收**；槓桿型、反向型不適用停徵 |
| 平均成本 | **移動平均法**：買進時，持有成本加上「成交金額＋手續費」；賣出不改變剩下股票的平均成本 |
| 賣出分攤的成本 | 持有成本 × 賣出股數 ÷ 持有股數，四捨五入到分；**全部賣光時就是剩下的成本全部**（不會留下零頭） |
| 已實現損益 | 賣出金額 − 賣出手續費 − 證交稅 − 分攤的成本 |
| 未實現損益 | 假設用收盤價全部賣出：市值 − 賣出手續費 − 證交稅 − 持有成本 |
| 收益率 | 未實現損益 ÷ 持有成本 × 100，四捨五入到小數第二位 |
| 順序 | 依交易日期，同一天依輸入的先後；補登或修改過去的交易，之後的數字自動重算 |

範例（[5.3](#53-交易明細-get-apiv2holdingscodetrades) 的資料）：

- 買 300 股 @600：180,000 ＋ 手續費 256 ＝ 180,256
- 買 500 股 @620：310,000 ＋ 手續費 441 ＝ 310,441 → 持有 800 股、成本 490,697
- 賣 200 股 @700：分攤成本 490,697 × 200 ÷ 800 ＝ 122,674.25；已實現 140,000 − 199 − 420 − 122,674.25 ＝ **16,706.75**
- 剩 600 股、成本 368,022.75、平均成本 **613.37**；收盤 650 → 未實現 390,000 − 555 − 1,170 − 368,022.75 ＝ **20,252.25**（**5.50%**）

---

## 9. 和 v1 的差異

| 項目 | v1（App 1.0） | v2（App 2.0） |
|---|---|---|
| 單位 | 張（整數） | 股（可記零股） |
| 一檔持股 | 一筆「張數＋買進價」 | 多筆買進、賣出的交易紀錄 |
| 識別 | 持股 id | 股票代號（交易用交易 id） |
| 金額 | 整數（元） | 兩位小數的字串（到分） |
| 已實現損益 | 沒有 | 有 |
| 防止重送 | 沒有 | `Idempotency-Key`（必帶） |
| 同時修改 | 以伺服器收到的順序為準 | 版本號，不會默默覆蓋 |

v1 的回應帶有 `Deprecation` 與 `Link` 標頭（預告未來會停止），App 2.0 請只用 v2。
