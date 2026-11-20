package io.qiao.api;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 取代 Spring Boot 預設的 /error 處理，讓 /api 底下「沒有經過 Controller 的錯誤」也回 App 規格的錯誤格式。
 *
 * 為什麼需要（技術陷阱）：
 * /api/v1/xxx 這種不存在的網址、或對 /api/v1/summary 送 POST，根本不會進到任何 Controller，
 * 所以 ApiExceptionHandler 接不到；Spring 會轉給 /error，預設回傳
 * {"timestamp":..., "status":404, "error":"Not Found", "path":...}，App 看不懂 → 顯示「資料格式錯誤」。
 *
 * 做法：繼承 Spring Boot 的 BasicErrorController，只改「回 JSON」的那個方法：
 * - 原本的網址是 /api 開頭 → 回 {"error":{"code","message"}}
 * - 其他（網頁）→ 照 Spring Boot 原本的行為（瀏覽器看到的錯誤頁不變）
 */
public class ApiAwareErrorController extends BasicErrorController {

	public ApiAwareErrorController(ErrorAttributes errorAttributes, ErrorProperties errorProperties,
			List<ErrorViewResolver> errorViewResolvers) {
		super(errorAttributes, errorProperties, errorViewResolvers);
	}

	@Override
	public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
		Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		if (originalUri == null || !originalUri.toString().startsWith("/api/")) {
			return super.error(request);
		}

		HttpStatus status = getStatus(request);
		ApiErrorCode code = switch (status) {
			case NOT_FOUND -> ApiErrorCode.NOT_FOUND;
			case METHOD_NOT_ALLOWED -> ApiErrorCode.METHOD_NOT_ALLOWED;
			case BAD_REQUEST, UNSUPPORTED_MEDIA_TYPE -> ApiErrorCode.VALIDATION_ERROR;
			default -> ApiErrorCode.INTERNAL_ERROR;
		};
		String message = switch (code) {
			case NOT_FOUND -> "找不到這個 API 路徑";
			case METHOD_NOT_ALLOWED -> "這個 API 路徑不支援這個 HTTP 方法";
			case VALIDATION_ERROR -> ApiFieldMessages.MALFORMED;
			default -> "伺服器發生錯誤，請稍後再試";
		};
		Map<String, Object> body = Map.of("error", Map.of("code", code.name(), "message", message));
		// 狀態碼照實回傳（例如 404、405），讓 App 的小字顯示正確的 HTTP 狀態碼
		return ResponseEntity.status(status).body(body);
	}
}
