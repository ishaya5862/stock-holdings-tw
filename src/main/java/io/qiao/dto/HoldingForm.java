package io.qiao.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import io.qiao.validation.StockCodes;

/**
 * 網頁表單（新增 / 修改持股）接收的資料。
 *
 * 為什麼不直接用 Entity 接表單？
 * - 避免使用者多送 id、createdAt 等欄位去竄改資料。
 * - 第 5 題的 API 欄位命名（buy_price）和驗證方式不同，會另外做 API 專用的 DTO。
 */
public class HoldingForm {

	// 格式規則統一放在 StockCodes（前端即時檢查、App API 共用同一份）
	@NotBlank(message = "請輸入股票代號")
	@Pattern(regexp = StockCodes.PATTERN_OR_EMPTY, message = StockCodes.MESSAGE)
	private String code;

	// 用 Integer 而不是 int：沒填時是 null，才能被 @NotNull 抓到並顯示「請輸入」
	@NotNull(message = "請輸入張數")
	@Min(value = 1, message = "張數最少 1 張")
	@Max(value = 999, message = "張數最多 999 張")
	private Integer lots;

	// 金額一律用 BigDecimal，避免 double 的誤差（例如 0.1 + 0.2 ≠ 0.3）
	@NotNull(message = "請輸入買進價")
	@DecimalMin(value = "0", inclusive = false, message = "買進價必須大於 0")
	@Digits(integer = 8, fraction = 2, message = "買進價最多兩位小數")
	private BigDecimal buyPrice;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		// 先統一寫法再驗證：去掉所有空白、全形轉半形、轉大寫（「　２３ ３０」→「2330」），
		// 使用者多打空白或用全形輸入，不應該被判成格式錯誤
		this.code = StockCodes.normalize(code);
	}

	public Integer getLots() {
		return lots;
	}

	public void setLots(Integer lots) {
		this.lots = lots;
	}

	public BigDecimal getBuyPrice() {
		return buyPrice;
	}

	public void setBuyPrice(BigDecimal buyPrice) {
		this.buyPrice = buyPrice;
	}
}
