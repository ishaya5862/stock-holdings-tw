package io.qiao.api;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import io.qiao.exception.UseApiV2Exception;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/v1 的每個回應：標示「已淘汰」（M3），並擋下 App 2.0 的誤用（M4）。2.0 第 6 題 C4。
 *
 * M3（業界標準，RFC 9745〈The Deprecation HTTP Response Header Field〉，2025 年 3 月）：
 * - Deprecation: @<Unix 秒數>   結構化日期（RFC 9651 Date），表示從什麼時候起淘汰；淘汰不改變 API 的行為。
 * - Link: <文件網址>; rel="deprecation"   指向給人看的淘汰說明。
 * - 不加 Sunset（RFC 8594，停止服務的時間）：App 1.0 半年後仍有三成使用者，還沒有停止 v1 的日期。
 * App 1.0 不讀這些標頭，完全不受影響；開發者用瀏覽器、Postman、curl 一看就知道這是舊版 API。
 *
 * M4：App 2.0 依 API.md 每個請求帶 X-Client: app2；誤呼叫 v1 時回 400 USE_API_V2（開發階段就會發現）。
 * App 1.0 不會送這個標頭。
 */
public class ApiV1DeprecationInterceptor implements HandlerInterceptor {

	/** v1 淘汰的時間：2.0 上線（2026-11-21 00:00 台北時間）。用台北時區算出 Unix 秒數，不寫死數字 */
	static final long DEPRECATED_SINCE = LocalDateTime.of(2026, 11, 21, 0, 0)
			.atZone(ZoneId.of("Asia/Taipei")).toEpochSecond();

	/** 淘汰說明文件（repo 裡的 API.md；README 已公開 repo 網址） */
	static final String DEPRECATION_DOC = "https://github.com/ishaya5862/stock-holdings-tw/blob/main/API.md";

	static final String CLIENT_HEADER = "X-Client";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 先加標頭：之後就算回錯誤（例如 404、409），回應也一樣帶著淘汰資訊
		response.setHeader("Deprecation", "@" + DEPRECATED_SINCE);
		response.setHeader("Link", "<" + DEPRECATION_DOC + ">; rel=\"deprecation\"; type=\"text/markdown\"");

		// 只在對應到 Controller 方法時檢查（例外才會交給 ApiExceptionHandler，回 v1 規格的錯誤格式）
		String client = request.getHeader(CLIENT_HEADER);
		if (handler instanceof HandlerMethod && client != null && client.trim().toLowerCase(Locale.ROOT).startsWith("app2")) {
			throw new UseApiV2Exception();
		}
		return true;
	}
}
