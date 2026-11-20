package io.qiao.controller;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 每個網頁都需要的共用資料（目前是頁首的台北時間）。
 * 放在這裡統一提供，各個 Controller 就不用每次自己加。
 * 範圍限定在 controller 套件（網頁），第 5 題的 API 不需要。
 */
@ControllerAdvice(basePackages = "io.qiao.controller")
public class LayoutModelAdvice {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Clock clock;

	public LayoutModelAdvice(Clock clock) {
		this.clock = clock;
	}

	/** 目前時間（用台北時區的 Clock，不受主機時區影響） */
	@ModelAttribute("now")
	public String now() {
		return ZonedDateTime.now(clock).format(TIME_FORMAT);
	}
}
