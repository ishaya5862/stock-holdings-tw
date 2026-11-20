package io.qiao.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首頁：顯示「持股記帳」標題與目前時間（台北時間）。
 * 時間由 LayoutModelAdvice 統一提供。
 */
@Controller
public class HomeController {

	@GetMapping("/")
	public String index() {
		return "index";
	}
}
