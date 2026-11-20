package io.qiao.service;

/**
 * 一個帶 Idempotency-Key 的寫入請求（2.0 第 6 題 C7）。由 API 層解析好後傳給 TradeService。
 *
 * @param key            App 帶的 key（已檢查格式）
 * @param requestHash    請求指紋（方法＋網址＋解析後欄位的 SHA-256）
 * @param method         POST／PUT／DELETE
 * @param path           網址（例：/api/v2/holdings/2330/trades）
 * @param responseStatus 成功時的狀態碼（新增 201、修改與刪除 200），重送時照用
 */
public record IdempotencyRequest(String key, String requestHash, String method, String path, int responseStatus) {
}
