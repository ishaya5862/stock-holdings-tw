package io.qiao.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 計算用的一筆交易（只有原始事實：買或賣、日期、股數、價格）。
 * 手續費、證交稅、已實現損益都不存，每次依規則重算（資料表設計 N1：完全正規化）。
 *
 * @param id        交易編號；同一天的交易依 id 排序（輸入順序）。還沒存檔的新交易為 null，排在同一天最後
 * @param side      買進或賣出
 * @param tradeDate 交易日期（台灣日期，沒有時區問題；「今天」由服務層用台北時區的 Clock 決定）
 * @param shares    股數（> 0）；用 long，多筆加總超過 INT 上限也不會溢位
 * @param price     每股價格（> 0，最多兩位小數）
 */
public record TradeInput(Long id, TradeSide side, LocalDate tradeDate, long shares, BigDecimal price) {
}
