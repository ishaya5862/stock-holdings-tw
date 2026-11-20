package io.qiao.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.support.RequestContextUtils;

import io.qiao.api.v2.HoldingV2Response;
import io.qiao.api.v2.SummaryV2Response;
import io.qiao.api.v2.TradeV2Response;
import io.qiao.exception.NotHeldException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.quote.TradingCalendar;
import io.qiao.service.PositionQueryService;
import io.qiao.service.PositionQueryService.PositionView;
import io.qiao.service.TradeService;
import io.qiao.validation.StockCodes;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 持股記帳 2.0 的新網頁：讀取（第 6 題 C8）。
 *
 * | 路徑                 | 頁面                                       |
 * | GET /v2              | 持股總覽（摘要卡＋持股表，含已全部賣出）      |
 * | GET /v2/holdings/2330 | 一檔的交易明細                             |
 * | GET /v2/buy[?code=]  | 買進（C9）                                  |
 * | GET /v2/sell?code=   | 賣出（C9）                                  |
 * | GET /v2/trades/{id}/edit | 修改一筆交易（C9；刪除在明細頁確認）     |
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
	private final TradingCalendar tradingCalendar;

	public V2PageController(PositionQueryService positionQueryService, TradingCalendar tradingCalendar) {
		this.positionQueryService = positionQueryService;
		this.tradingCalendar = tradingCalendar;
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

	// ===== 寫入的頁面（C9）：送出由頁面的 JavaScript 呼叫 /api/v2，這裡只準備畫面需要的資料 =====

	/**
	 * 買進頁。
	 * - /v2/buy?code=2330：從持股列進來，代號固定不能改
	 * - /v2/buy：從「記一筆買進」進來，代號自己輸入（可以買新的股票）
	 * 目前持股的股數與成本放進頁面，輸入代號時顯示「目前持有」與買進後的平均成本試算（只是試算，以伺服器為準）。
	 */
	@GetMapping("/buy")
	public String buyForm(@RequestParam(required = false) String code, Model model) {
		List<HoldingV2Response> holdings = positionQueryService.findAll().stream().map(HoldingV2Response::from).toList();
		model.addAttribute("fixedCode", code == null || code.isBlank() ? null : StockCodes.normalize(code));
		model.addAttribute("heldByCode", heldByCode(holdings));
		addCalendar(model);
		return "v2/buy";
	}

	/** 賣出頁（/v2/sell?code=2330）：只能賣目前持有的（0 股或資料異常 → 回總覽並說明） */
	@GetMapping("/sell")
	public String sellForm(@RequestParam(required = false) String code, Model model, HttpServletRequest request) {
		HoldingV2Response holding = HoldingV2Response.from(positionQueryService.findByCode(code == null ? "" : code));
		if (holding.dataError() != null) {
			return redirectWithError(request, holding.code() + " 的交易資料異常，請先到明細修正：" + holding.dataError());
		}
		if (holding.shares() == 0) {
			return redirectWithError(request, holding.code() + " 目前沒有持股（已全部賣出），不能賣出");
		}
		model.addAttribute("holding", holding);
		model.addAttribute("held", heldInfo(holding));
		addCalendar(model);
		return "v2/sell";
	}

	/** 修改一筆交易（C8-4 a 獨立頁面）：日期、股數、價格可以改，買或賣不能改（C6）；版本號放在頁面，送出時帶上 */
	@GetMapping("/trades/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		PositionView view = positionQueryService.findByTradeId(id);
		TradeV2Response trade = TradeV2Response.listOf(view).stream()
				.filter(t -> t.id().equals(id))
				.findFirst()
				.orElseThrow(() -> new TradeNotFoundException(id));
		model.addAttribute("holding", HoldingV2Response.from(view));
		model.addAttribute("trade", trade);
		addCalendar(model);
		return "v2/edit";
	}

	/** 目前持有（0 股與資料異常除外）：代號 → 名稱、股數、持有成本（給買進頁試算） */
	private static Map<String, Map<String, Object>> heldByCode(List<HoldingV2Response> holdings) {
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		for (HoldingV2Response h : holdings) {
			if (h.dataError() == null) {
				result.put(h.code(), heldInfo(h));
			}
		}
		return result;
	}

	/**
	 * 頁面 JavaScript 用的持股資料。只放字串與數字（不放 LocalDate 等物件），轉成 JavaScript 時格式一定正確。
	 * 金額是兩位小數的字串，JavaScript 用 BigInt 以「分」計算（最大約 10^16 元，一般數字會失真）。
	 */
	private static Map<String, Object> heldInfo(HoldingV2Response h) {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", h.name() == null ? "" : h.name());
		info.put("shares", h.shares());
		info.put("total_cost", h.totalCost());
		return info;
	}

	/**
	 * 已知的休市日（C8-5 a：放進頁面，開頁不向官方查詢）：年份 → 日期字串。
	 * 週末一律休市，由 JavaScript 判斷；沒有資料的年份（2020 以前等）不在裡面 → 網頁只檢查週末並註明（C8-6）。
	 */
	private void addCalendar(Model model) {
		Map<String, List<String>> closedDays = new TreeMap<>();
		tradingCalendar.knownClosedDays().forEach((year, days) ->
				closedDays.put(String.valueOf(year), days.stream().sorted().map(LocalDate::toString).toList()));
		model.addAttribute("closedDays", closedDays);
		model.addAttribute("earliestDate", TradeService.EARLIEST_DATE.toString());
	}

	private static String redirectWithError(HttpServletRequest request, String message) {
		RequestContextUtils.getOutputFlashMap(request).put("errorMessage", message);
		return "redirect:/v2";
	}

	/**
	 * 沒有這檔持股（例如另一個裝置剛把最後一筆交易刪了，持股跟著刪除）：回總覽並提示，不顯示錯誤頁。
	 * 寫在這個 Controller 裡：只影響 /v2 的頁面，第一版網頁的處理不變。
	 */
	@ExceptionHandler(NotHeldException.class)
	public String handleNotHeld(NotHeldException e, HttpServletRequest request) {
		return redirectWithError(request, "目前沒有這檔持股，可能已在另一個裝置刪除");
	}

	/** 沒有這筆交易（另一個裝置剛刪掉，或網址的 id 不是數字）：回總覽並提示 */
	@ExceptionHandler({ TradeNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public String handleTradeNotFound(HttpServletRequest request) {
		return redirectWithError(request, "找不到這筆交易，可能已在另一個裝置刪除");
	}
}
