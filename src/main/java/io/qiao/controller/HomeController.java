package io.qiao.controller;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首頁：顯示「持股記帳」標題與目前時間（台北時間）。
 */
@Controller
public class HomeController {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Clock clock;

	public HomeController(Clock clock) {
		this.clock = clock;
	}

	@GetMapping("/")
	public String index(Model model) {
		// 用注入的 Clock（時區固定為台北），不受主機時區影響
		ZonedDateTime now = ZonedDateTime.now(clock);
		model.addAttribute("now", now.format(TIME_FORMAT));
		model.addAttribute("zone", now.getZone().getId());
		return "index";
	}
}
