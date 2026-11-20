package io.qiao.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首頁：確認框架（Spring MVC + Thymeleaf）可以正常運作。
 */
@Controller
public class HomeController {

	@GetMapping("/")
	public String index(Model model) {
		model.addAttribute("message", "專案啟動成功");
		// 回傳 "index" → 對應 src/main/resources/templates/index.html
		return "index";
	}
}
