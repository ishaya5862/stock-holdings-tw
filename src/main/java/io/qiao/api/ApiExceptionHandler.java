package io.qiao.api;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.databind.JsonMappingException;

import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingBusyException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.UpgradeRequiredException;
import io.qiao.exception.UseApiV2Exception;

/**
 * API 的統一錯誤處理：所有錯誤一律回 {"error":{"code","message"}}（App 1.0 規格）。
 *
 * 範圍：只處理 io.qiao.api 套件的 Controller 丟出的例外（網頁有自己的 WebExceptionHandler）。
 * 不經過 Controller 的錯誤（/api 底下不存在的網址、HTTP 方法不支援）由 ApiAwareErrorController 處理。
 *
 * @Order(HIGHEST_PRECEDENCE)：確保 API 的例外一定由這裡處理，不會被其他全域的處理搶走。
 */
@RestControllerAdvice(basePackages = "io.qiao.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(HoldingNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleHoldingNotFound() {
		return error(ApiErrorCode.NOT_FOUND, "找不到這筆持股，可能已被刪除");
	}

	/** 網址的 id 不是數字（例如 /api/v1/holdings/abc）：當成找不到 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiErrorResponse> handleTypeMismatch() {
		return error(ApiErrorCode.NOT_FOUND, "找不到這筆持股，可能已被刪除");
	}

	@ExceptionHandler(DuplicateCodeException.class)
	public ResponseEntity<ApiErrorResponse> handleDuplicate(DuplicateCodeException e) {
		return error(ApiErrorCode.DUPLICATE_CODE, e.getMessage()); // 例：「2330 已在持股中」
	}

	@ExceptionHandler(StockNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleStockNotFound(StockNotFoundException e) {
		return error(ApiErrorCode.STOCK_NOT_FOUND, e.getMessage());
	}

	/** 2.0：1.0 無法修改或刪除這筆持股 → 409，訊息請使用者更新到 App 2.0（N-a） */
	@ExceptionHandler(UpgradeRequiredException.class)
	public ResponseEntity<ApiErrorResponse> handleUpgradeRequired(UpgradeRequiredException e) {
		return error(ApiErrorCode.UPGRADE_REQUIRED, e.getMessage());
	}

	/** 2.0：等鎖超過 3 秒（例如手機與電腦同時操作同一檔）→ 409，App 1.0 會顯示「資料正在更新，請稍後再試」 */
	@ExceptionHandler(HoldingBusyException.class)
	public ResponseEntity<ApiErrorResponse> handleHoldingBusy(HoldingBusyException e) {
		log.warn("等鎖逾時：{}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
		return error(ApiErrorCode.HOLDING_BUSY, HoldingBusyException.USER_MESSAGE);
	}

	/** 2.0：App 2.0 呼叫了 v1（M4） */
	@ExceptionHandler(UseApiV2Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUseApiV2(UseApiV2Exception e) {
		return error(ApiErrorCode.USE_API_V2, e.getMessage());
	}

	@ExceptionHandler(QuoteUnavailableException.class)
	public ResponseEntity<ApiErrorResponse> handleQuoteUnavailable(QuoteUnavailableException e) {
		log.warn("API 新增持股時官方行情連不上：{}", e.getMessage());
		return error(ApiErrorCode.QUOTE_UNAVAILABLE, QuoteUnavailableException.USER_MESSAGE);
	}

	/**
	 * Bean Validation 檢查失敗（缺欄位、超出範圍、超過兩位小數）：所有錯誤欄位的訊息合併成一句，
	 * 用「；」隔開（例：「請輸入股票代號；張數必須是 1～999 的整數」），App 一次就看到全部要改的地方。
	 *
	 * 順序固定照「代號 → 張數 → 買進價」（驗證工具回報的順序不固定，固定後同樣的輸入每次訊息都一樣）；
	 * 同一欄位有多個規則失敗（例如張數 null 同時違反好幾個）時只留一句。
	 *
	 * 註：型別錯誤（例如張數送 "abc"）在讀取 JSON 時就被擋下，還輪不到這裡，所以只會顯示那一則（見 handleNotReadable）。
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		List<FieldError> errors = e.getBindingResult().getFieldErrors();
		String message = FIELD_ORDER.stream()
				.flatMap(field -> errors.stream().filter(error -> error.getField().equals(field)))
				.map(FieldError::getDefaultMessage)
				.distinct()
				.collect(Collectors.joining("；"));
		return error(ApiErrorCode.VALIDATION_ERROR, message.isEmpty() ? ApiFieldMessages.MALFORMED : message);
	}

	/** 驗證錯誤訊息的優先順序（Java 欄位名稱） */
	private static final List<String> FIELD_ORDER = List.of("code", "lots", "buyPrice");

	/**
	 * JSON 讀不進來：格式壞掉，或欄位型別不對（張數送 1.5、"1"、"abc"，買進價送字串）。
	 * Jackson 已設定成不自動轉換（application.properties），型別不對一定會到這裡。
	 * 從例外裡找出是哪個欄位，回那個欄位的中文訊息。
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return error(ApiErrorCode.VALIDATION_ERROR, messageFor(e));
	}

	/** 送出的不是 JSON（Content-Type 不對） */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMediaType() {
		return error(ApiErrorCode.VALIDATION_ERROR, ApiFieldMessages.MALFORMED);
	}

	/** 其他沒預料到的錯誤：記 log 給開發者看，使用者只看到通用訊息（不洩漏程式細節） */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
		log.error("API 發生未預期的錯誤", e);
		return error(ApiErrorCode.INTERNAL_ERROR, "伺服器發生錯誤，請稍後再試");
	}

	static ResponseEntity<ApiErrorResponse> error(ApiErrorCode code, String message) {
		return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(code, message));
	}

	/** 找出是哪個 JSON 欄位造成錯誤；找不到（例如整個 JSON 壞掉）就用通用訊息 */
	private static String messageFor(HttpMessageNotReadableException e) {
		if (e.getCause() instanceof JsonMappingException mappingException) {
			List<JsonMappingException.Reference> path = mappingException.getPath();
			if (!path.isEmpty() && path.get(0).getFieldName() != null) {
				return ApiFieldMessages.forField(path.get(0).getFieldName());
			}
		}
		return ApiFieldMessages.MALFORMED;
	}
}
