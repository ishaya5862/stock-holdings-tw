package io.qiao.api.v2;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.qiao.calculation.TradeLine;
import io.qiao.entity.Trade;
import io.qiao.service.PositionQueryService;
import io.qiao.service.PositionQueryService.PositionView;

/**
 * App 2.0 API 的讀取（第 6 題 C5）。路徑一律 /api/v2，回應 JSON（UTF-8）；規格寫在 API.md（第 8 題）。
 *
 * - 一檔持股用股票代號識別（C5-1 a）：/api/v2/holdings/2330；代號會先統一寫法（全形、小寫、空白）。
 * - 金額、價格是兩位小數的字串（C5-2 b），欄位名稱小寫加底線（C5-3 a，和 v1 相同）。
 * - 錯誤格式和 v1 相同 {"error":{"code","message"}}（ApiExceptionHandler 負責整個 io.qiao.api 套件，C5-7 a）。
 * - 和 v1 完全分開：v1 的名稱加註、張數捨去、淘汰標頭都不會出現在這裡（M1）。
 */
@RestController
@RequestMapping("/api/v2")
public class ApiV2HoldingController {

	private final PositionQueryService positionQueryService;

	public ApiV2HoldingController(PositionQueryService positionQueryService) {
		this.positionQueryService = positionQueryService;
	}

	/** 持股清單：200，持股物件陣列（含已全部賣出、shares = 0 的持股）；沒有持股回 [] */
	@GetMapping("/holdings")
	public List<HoldingV2Response> list() {
		return positionQueryService.findAll().stream().map(HoldingV2Response::from).toList();
	}

	/** 單一持股：200，持股物件；沒有持有過這個代號 → 404 NOT_HELD */
	@GetMapping("/holdings/{code}")
	public HoldingV2Response get(@PathVariable String code) {
		return HoldingV2Response.from(positionQueryService.findByCode(code));
	}

	/** 某一檔的交易明細：200，交易物件陣列（依交易日期、輸入順序）；沒有持有過 → 404 NOT_HELD */
	@GetMapping("/holdings/{code}/trades")
	public List<TradeV2Response> trades(@PathVariable String code) {
		PositionView view = positionQueryService.findByCode(code);
		List<Trade> trades = view.trades();
		if (view.hasDataError()) {
			// 資料異常、算不出來：照樣列出原始交易，使用者才能找出並修正錯的那一筆（朋友回饋 1-1 a）
			return trades.stream().map(TradeV2Response::raw).toList();
		}
		List<TradeLine> lines = view.position().lines();
		// 兩者都依「交易日期 → id」排序（資料庫查詢與 PositionCalculator 同一個規則），第 i 筆互相對應
		return IntStream.range(0, trades.size())
				.mapToObj(i -> TradeV2Response.from(trades.get(i), lines.get(i)))
				.toList();
	}

	/** 總計：200，總計物件 */
	@GetMapping("/summary")
	public SummaryV2Response summary() {
		return SummaryV2Response.from(positionQueryService.findAll());
	}
}
