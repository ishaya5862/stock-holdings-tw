package io.qiao.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 某一天的收盤價（官方回應 data 陣列裡的一列，只取需要的兩個欄位）。
 */
public record DailyClose(LocalDate date, BigDecimal closePrice) {
}
