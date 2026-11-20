package io.qiao.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.qiao.calculation.ProfitResult;
import io.qiao.dto.HoldingView;

/**
 * App 1.0 API 的「持股物件」。欄位名稱、型別完全照規格（App 已發布，不會再改）：
 *
 * | 欄位          | 型別          | 說明                                   |
 * | id            | 整數          | App 修改、刪除都用它                   |
 * | code          | 字串          | 股票代號                               |
 * | name          | 字串          | 股票名稱                               |
 * | market        | 字串          | TWSE（上市）或 TPEX（上櫃）            |
 * | lots          | 整數          | 張數                                   |
 * | buy_price     | 數字          | 買進價                                 |
 * | current_price | 數字或 null   | 最近收盤價；取不到時為 null            |
 * | price_date    | 字串或 null   | 收盤價日期 YYYY-MM-DD；取不到時為 null |
 * | pnl           | 整數或 null   | 盈虧（元）；取不到收盤價時為 null      |
 * | return_pct    | 數字或 null   | 收益率（%）；取不到收盤價時為 null     |
 *
 * 陷阱（讀 App 原始碼 Contract.java 發現）：
 * - 欄位名稱用小寫加底線 → 每個欄位都用 @JsonProperty 明確指定，不依賴全域命名設定。
 * - 可為 null 的欄位「一定要出現」（值為 null），省略會被 App 判定「少了 pnl」→ 不可設定 Jackson 省略 null。
 * - pnl 必須是 JSON 整數：用 Long，不能輸出 93708.00（App 會判定成小數 →「資料格式錯誤」）。
 * - price_date：LocalDate 會輸出成 "2026-11-19"（Spring Boot 預設 ISO 格式，不是時間戳）。
 */
public record HoldingResponse(
		@JsonProperty("id") Long id,
		@JsonProperty("code") String code,
		@JsonProperty("name") String name,
		@JsonProperty("market") String market,
		@JsonProperty("lots") Integer lots,
		@JsonProperty("buy_price") BigDecimal buyPrice,
		@JsonProperty("current_price") BigDecimal currentPrice,
		@JsonProperty("price_date") LocalDate priceDate,
		@JsonProperty("pnl") Long pnl,
		@JsonProperty("return_pct") BigDecimal returnPct) {

	/**
	 * 由清單資料轉成 API 格式。
	 * 呼叫前要先確認 market 不是 null（見 ApiHoldingController：市場別空白的舊資料不輸出）。
	 */
	static HoldingResponse from(HoldingView view) {
		ProfitResult profit = view.profit();
		return new HoldingResponse(
				view.id(),
				view.code(),
				// 名稱還沒查到的舊資料：用代號代替（規格規定 name 不可為 null）
				view.name() != null ? view.name() : view.code(),
				view.market().name(),
				view.lots(),
				view.buyPrice(),
				profit != null ? view.closePrice() : null,
				profit != null ? view.priceDate() : null,
				profit != null ? toWholeDollars(profit.pnl()) : null,
				profit != null ? profit.returnPct() : null);
	}

	/**
	 * 金額轉成整數元。1.0 版的金額一定是整數（張數 × 1,000 × 最多兩位小數的價格，手續費和稅都已捨去到元），
	 * 這裡只是把 BigDecimal 的 93708.00 轉成 Long 的 93708，輸出才會是 JSON 整數。
	 */
	static Long toWholeDollars(BigDecimal amount) {
		return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}
}
