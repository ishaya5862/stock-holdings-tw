# 休市日備援資料

這個資料夾的 `年份.json` 是證交所「市場開休市日期」的原始回應，下載後原封不動存放，作為休市日判斷的**備援**：
程式優先查官方 API，查不到（例如沒有網路，或驗收用的測試伺服器沒有這支 API）時改讀這裡的檔案。

| 檔案 | 來源 | 下載日期 |
|---|---|---|
| 2026.json | https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?date=20260101&response=json | 2026-11-20 |
| 2027.json | https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?date=20270101&response=json | 2026-11-20 |

**每年更新**：證交所公布隔年休市日後，用上面的網址（把 date 換成隔年的 0101）下載，存成 `隔年.json` 放進這個資料夾。
沒有對應年份的檔案、官方也查不到時，程式一律把平日當成開盤日，排程照常更新。
