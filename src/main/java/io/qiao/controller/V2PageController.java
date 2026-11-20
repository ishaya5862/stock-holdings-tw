package io.qiao.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.RequestContextUtils;

import io.qiao.api.v2.HoldingV2Response;
import io.qiao.api.v2.SummaryV2Response;
import io.qiao.api.v2.TradeV2Response;
import io.qiao.exception.NotHeldException;
import io.qiao.service.PositionQueryService;
import io.qiao.service.PositionQueryService.PositionView;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 持股記帳 2.0 的新網頁：讀取（第 6 題 C8）。
 *
 * | 路徑                 | 頁面                                       |
 * | GET /v2              | 持股總覽（摘要卡＋持股表，含已全部賣出）      |
 * | GET /v2/holdings/2330 | 一檔的交易明細                             |
 *
 * 做法（C8-1 a）：畫面由伺服器產生（沒有 JavaScript 也看得到）；資料直接用 App 2.0 API 的回應物件
 * （HoldingV2Response 等），網頁和 App 2.0 的數字一定相同。寫入（買賣、修改、刪除）由頁面的 JavaScript
 * 呼叫 /api/v2（C9），沿用 Idempotency-Key 防重送。
 * 只讀資料庫與行情快取，開頁不會向官方查詢（題目第 3 題要求 3）。
 *
 * 第一版網頁 /holdings 照舊保留（C8-2 a），路徑分開，不影響舊書籤與 App 1.0。
 */
@Controller
@RequestMapping("/v2")
public class V2PageController {

	private final PositionQueryService positionQueryService;

	public V2PageController(PositionQueryService positionQueryService) {
		this.positionQueryService = positionQueryService;
	}

	/** 持股總覽 */
	@GetMapping
	public String overview(Model model) {
		// 只查一次：持股表與摘要卡用同一份資料（同一個時間點），數字彼此一致
		List<PositionView> views = positionQueryService.findAll();
		List<HoldingV2Response> holdings = views.stream().map(HoldingV2Response::from).toList();
		model.addAttribute("holdings", holdings);
		model.addAttribute("summary", SummaryV2Response.from(views));
		model.addAttribute("closedCount", holdings.stream().filter(h -> Long.valueOf(0).equals(h.shares())).count());
		return "v2/overview";
	}

	/** 一檔的交易明細（代號會先統一寫法，/v2/holdings/２３３０ 也看得到 2330） */
	@GetMapping("/holdings/{code}")
	public String detail(@PathVariable String code, Model model) {
		PositionView view = positionQueryService.findByCode(code);
		model.addAttribute("holding", HoldingV2Response.from(view));
		model.addAttribute("trades", TradeV2Response.listOf(view));
		return "v2/detail";
	}

	/**
	 * 沒有這檔持股（例如另一個裝置剛把最後一筆交易刪了，持股跟著刪除）：回總覽並提示，不顯示錯誤頁。
	 * 寫在這個 Controller 裡：只影響 /v2 的頁面，第一版網頁的處理不變。
	 */
	@ExceptionHandler(NotHeldException.class)
	public String handleNotHeld(NotHeldException e, HttpServletRequest request) {
		RequestContextUtils.getOutputFlashMap(request).put("errorMessage", "目前沒有這檔持股，可能已在另一個裝置刪除");
		return "redirect:/v2";
	}
}
