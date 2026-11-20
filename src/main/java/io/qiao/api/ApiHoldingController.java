package io.qiao.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.qiao.calculation.PortfolioSummary;
import io.qiao.dto.HoldingView;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.service.HoldingService;

/**
 * App 1.0 API（第 5 題）。路徑一律 /api/v1，回應 JSON；規格見 README「App 1.0 API」。
 * 錯誤一律由 ApiExceptionHandler／ApiAwareErrorController 回 {"error":{"code","message"}}。
 *
 * 和網頁共用 HoldingService（新增、修改、刪除、盈虧計算的規則只寫一次），這裡只負責「轉成規格的格式」。
 *
 * 市場別空白的舊資料（第 2 題時期新增、排程一直查不到）：規格規定 market 只能是 TWSE／TPEX，
 * 輸出 null 會讓 App 整個清單顯示「資料格式錯誤」→ 第 5 題決定 6A：這幾筆暫時不出現在 App（清單、單筆、總計），
 * 網頁照常顯示；排程補上市場別後就會自動出現。
 */
@RestController
@RequestMapping("/api/v1")
public class ApiHoldingController {

	private final HoldingService holdingService;

	public ApiHoldingController(HoldingService holdingService) {
		this.holdingService = holdingService;
	}

	/** 持股清單：200，持股物件陣列；沒有持股回 [] */
	@GetMapping("/holdings")
	public List<HoldingResponse> list() {
		return exposable(holdingService.findAllViews()).stream()
				.map(HoldingResponse::from)
				.toList();
	}

	/** 單筆持股：200，持股物件；找不到 404 NOT_FOUND */
	@GetMapping("/holdings/{id}")
	public HoldingResponse get(@PathVariable Long id) {
		HoldingView view = holdingService.findViewById(id);
		if (view.market() == null) {
			throw new HoldingNotFoundException(id); // 市場別空白的舊資料，App 看不到（6A）
		}
		return HoldingResponse.from(view);
	}

	/** 總計：200，總計物件（計算規則與網頁頁面上方的總計相同） */
	@GetMapping("/summary")
	public SummaryResponse summary() {
		List<HoldingView> views = exposable(holdingService.findAllViews());
		return SummaryResponse.from(PortfolioSummary.of(views.stream().map(HoldingView::toSummaryLine).toList()));
	}

	/** App 看得到的持股：市場別已知（6A） */
	private static List<HoldingView> exposable(List<HoldingView> views) {
		return views.stream().filter(view -> view.market() != null).toList();
	}
}
