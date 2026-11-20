package io.qiao.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.qiao.calculation.PortfolioSummary;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.service.HoldingService;
import io.qiao.validation.StockCodes;
import jakarta.validation.Valid;

/**
 * App 1.0 API（第 5 題）。路徑一律 /api/v1，回應 JSON；規格見 README「App 1.0 API」。
 * 錯誤一律由 ApiExceptionHandler／ApiAwareErrorController 回 {"error":{"code","message"}}。
 *
 * 和網頁共用 HoldingService（新增、修改、刪除、盈虧計算的規則只寫一次），這裡只負責「轉成規格的格式」。
 *
 * 市場別空白的舊資料（第 2 題時期新增、排程一直查不到）：規格規定 market 只能是 TWSE／TPEX，
 * 輸出 null 會讓 App 整個清單顯示「資料格式錯誤」→ 第 5 題決定 6A：這幾筆暫時不出現在 App（清單、單筆、總計），
 * 修改、刪除也當成找不到（App 本來就看不到它們的 id），網頁照常顯示；排程補上市場別後就會自動出現。
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
		return HoldingResponse.from(findExposable(id));
	}

	/**
	 * 新增持股：201，新建的持股物件。
	 * 失敗：400 VALIDATION_ERROR（欄位缺少、型別或範圍不對）、404 STOCK_NOT_FOUND、409 DUPLICATE_CODE，
	 * 另外官方連不上時 503 QUOTE_UNAVAILABLE（D-1 決定）。
	 */
	@PostMapping("/holdings")
	public ResponseEntity<HoldingResponse> create(@Valid @RequestBody CreateHoldingRequest request) {
		// 4B：代號有填、但格式不可能存在（例如 ABC、23）→ 直接回 404 查無，不必浪費一次官方查詢
		if (!StockCodes.isValid(request.code())) {
			throw new StockNotFoundException(request.code()); // code 已在 CreateHoldingRequest 統一寫法
		}
		Holding created = holdingService.create(request.code(), request.lots(), request.buyPrice());
		HoldingResponse body = HoldingResponse.from(holdingService.findViewById(created.getId()));
		// 201 Created 慣例會帶 Location 標頭，指向新資源的網址（App 不使用，但符合 HTTP 規範）
		return ResponseEntity.created(URI.create("/api/v1/holdings/" + created.getId())).body(body);
	}

	/**
	 * 修改持股：200，修改後的持股物件。代號不能改（請求帶了 code 也忽略）。
	 * 失敗：400 VALIDATION_ERROR、404 NOT_FOUND。
	 */
	@PutMapping("/holdings/{id}")
	public HoldingResponse update(@PathVariable Long id, @Valid @RequestBody UpdateHoldingRequest request) {
		findExposable(id); // 先確認 App 看得到這筆（找不到 → 404）
		holdingService.update(id, request.lots(), request.buyPrice());
		return HoldingResponse.from(holdingService.findViewById(id));
	}

	/** 刪除持股：204，沒有內容；找不到 404 NOT_FOUND */
	@DeleteMapping("/holdings/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		findExposable(id);
		holdingService.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** 總計：200，總計物件（計算規則與網頁頁面上方的總計相同） */
	@GetMapping("/summary")
	public SummaryResponse summary() {
		List<HoldingView> views = exposable(holdingService.findAllViews());
		return SummaryResponse.from(PortfolioSummary.of(views.stream().map(HoldingView::toSummaryLine).toList()));
	}

	/** 找 App 看得到的單筆持股；找不到或市場別空白（6A）→ 404 */
	private HoldingView findExposable(Long id) {
		HoldingView view = holdingService.findViewById(id);
		if (view.market() == null) {
			throw new HoldingNotFoundException(id);
		}
		return view;
	}

	/** App 看得到的持股：市場別已知（6A），而且資料沒有異常（朋友回饋 1-2 a：算不出張數、買進價，不給 App 錯的數字） */
	private static List<HoldingView> exposable(List<HoldingView> views) {
		return views.stream().filter(view -> view.market() != null && !view.hasDataError()).toList();
	}
}
