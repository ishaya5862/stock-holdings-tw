package io.qiao.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.qiao.validation.StockCodes;

/**
 * POST /api/v1/holdings 的請求內容：{ "code": "2330", "lots": 1, "buy_price": 1000 }
 *
 * App 1.0 不會先檢查輸入（App 原始碼 EditActivity）：
 * - 張數打 1.5 或 abc → 送出字串 "1.5"、"abc"；空白 → 送出 null
 * - 買進價打 abc → 送出字串 "abc"
 * 型別不對由 Jackson 擋（application.properties 已關閉自動轉換）→ 400；
 * 數值範圍由下面的 Bean Validation 擋 → 400。
 *
 * 代號格式（4～6 位數字＋可選 1 個英文字母）不在這裡檢查：
 * 第 5 題決定 4B「代號有填但格式不可能存在 → 404 STOCK_NOT_FOUND」，在 Controller 處理。
 */
public record CreateHoldingRequest(
		@JsonProperty("code")
		@NotBlank(message = ApiFieldMessages.CODE_REQUIRED)
		String code,

		// Integer（不是 int）：沒填時是 null，才能被 @NotNull 抓到
		@JsonProperty("lots")
		@NotNull(message = ApiFieldMessages.LOTS)
		@Min(value = 1, message = ApiFieldMessages.LOTS)
		@Max(value = 999, message = ApiFieldMessages.LOTS)
		Integer lots,

		@JsonProperty("buy_price")
		@NotNull(message = ApiFieldMessages.BUY_PRICE)
		@DecimalMin(value = "0", inclusive = false, message = ApiFieldMessages.BUY_PRICE)
		@Digits(integer = 8, fraction = 2, message = ApiFieldMessages.BUY_PRICE)
		BigDecimal buyPrice) {

	/**
	 * 建立物件時先統一代號寫法（去空白、全形轉半形、轉大寫），之後的檢查都用統一後的值。
	 * 為什麼要在這裡做：Java 的 trim() 和 @NotBlank 不認得全形空白，只送 "　" 會被當成有填，
	 * 變成 404「查無股票代號 　」；先統一後就是空字串，正確回 400「請輸入股票代號」。
	 * （網頁的 HoldingForm.setCode 也是同樣做法）
	 */
	public CreateHoldingRequest {
		code = StockCodes.normalize(code);
	}
}
