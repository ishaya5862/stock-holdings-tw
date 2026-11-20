package io.qiao.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/v1/holdings/{id} 的請求內容：{ "lots": 2, "buy_price": 980.5 }
 *
 * 規格：代號不能改。這個類別沒有 code 欄位，請求就算帶了 code 也會被忽略
 * （Spring Boot 預設忽略不認得的欄位），代號一律以資料庫為準。
 * 驗證規則與新增相同。
 */
public record UpdateHoldingRequest(
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
}
