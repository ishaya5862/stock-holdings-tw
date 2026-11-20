package io.qiao.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.support.RequestContextUtils;

import io.qiao.exception.HoldingNotFoundException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 網頁的統一例外處理。
 * 範圍限定在 controller 套件（網頁）；第 5 題的 API 會另外有回傳 JSON 錯誤格式的處理。
 */
@ControllerAdvice(basePackages = "io.qiao.controller")
public class WebExceptionHandler {

	/**
	 * 找不到持股（例如已被刪除，或網址的 id 不是數字）：
	 * 回到清單並顯示提示，而不是顯示錯誤頁。
	 */
	@ExceptionHandler({ HoldingNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public String handleNotFound(HttpServletRequest request) {
		// 用 FlashMap 帶訊息到轉址後的清單頁（效果同 RedirectAttributes.addFlashAttribute）
		RequestContextUtils.getOutputFlashMap(request).put("errorMessage", "找不到這筆持股，可能已被刪除");
		return "redirect:/holdings";
	}
}
