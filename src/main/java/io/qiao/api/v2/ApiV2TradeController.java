package io.qiao.api.v2;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.qiao.calculation.TradeLine;
import io.qiao.calculation.TradeSide;
import io.qiao.entity.Trade;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.service.PositionQueryService;
import io.qiao.service.PositionQueryService.PositionView;
import io.qiao.service.TradeResult;
import io.qiao.service.TradeService;
import io.qiao.validation.StockCodes;

/**
 * App 2.0 API 的寫入（第 6 題 C6）：買進、賣出、修改一筆交易、刪除一筆交易。
 *
 * | 方法   | 路徑                            | 成功 | 失敗                                                                     |
 * | POST   | /api/v2/holdings/{code}/trades  | 201  | 400 VALIDATION_ERROR、404 STOCK_NOT_FOUND／NOT_HELD、409 OVERSELL／HOLDING_BUSY、503 QUOTE_UNAVAILABLE |
 * | PUT    | /api/v2/trades/{id}             | 200  | 400 VALIDATION_ERROR、404 TRADE_NOT_FOUND、409 VERSION_CONFLICT／OVERSELL／HOLDING_BUSY |
 * | DELETE | /api/v2/trades/{id}?version=N   | 200  | 400 VALIDATION_ERROR、404 TRADE_NOT_FOUND、409 VERSION_CONFLICT／OVERSELL／HOLDING_BUSY |
 *
 * 規則與鎖都在 TradeService（C3）；這裡只負責「檢查請求格式 → 呼叫服務 → 轉成回應」。
 * 寫入後用 PositionQueryService 重新讀一次（和 GET 的結果完全相同），回傳交易＋重算後的持股（C6-3 a）。
 */
@RestController
@RequestMapping("/api/v2")
public class ApiV2TradeController {

	private final TradeService tradeService;
	private final PositionQueryService positionQueryService;

	public ApiV2TradeController(TradeService tradeService, PositionQueryService positionQueryService) {
		this.tradeService = tradeService;
		this.positionQueryService = positionQueryService;
	}

	/** 買進或賣出（內容的 side 決定，C6-1 a）。買進還沒持有的代號會先向官方確認代號存在 */
	@PostMapping("/holdings/{code}/trades")
	public ResponseEntity<TradeWriteResponse> create(@PathVariable String code,
			@RequestBody(required = false) JsonNode body) {
		TradeRequestParser.NewTrade request = TradeRequestParser.parseNew(body);
		String normalizedCode = StockCodes.normalize(code);

		TradeResult result;
		if (request.side() == TradeSide.BUY) {
			// 代號格式不可能存在（例如 ABC、23）→ 直接 404，不浪費一次官方查詢（和 v1 相同）
			if (!StockCodes.isValid(normalizedCode)) {
				throw new StockNotFoundException(normalizedCode);
			}
			result = tradeService.buy(normalizedCode, request.tradeDate(), request.shares(), request.price());
		} else {
			result = tradeService.sell(normalizedCode, request.tradeDate(), request.shares(), request.price());
		}

		Long tradeId = result.trade().getId();
		return ResponseEntity.created(URI.create("/api/v2/trades/" + tradeId))
				.body(responseFor(result.holding().getCode(), tradeId));
	}

	/** 修改一筆交易的日期、股數、價格（內容要帶 version，C6-2 b、C6-7 a） */
	@PutMapping("/trades/{id}")
	public TradeWriteResponse update(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
		Long tradeId = parseTradeId(id);
		TradeRequestParser.TradeChange change = TradeRequestParser.parseChange(body);
		TradeResult result = tradeService.updateTrade(tradeId, change.version(), change.tradeDate(), change.shares(),
				change.price());
		return responseFor(result.holding().getCode(), tradeId);
	}

	/** 刪除一筆交易（網址參數 ?version=，C6-2 b）。刪掉最後一筆時持股也一起刪除（C2-1） */
	@DeleteMapping("/trades/{id}")
	public TradeWriteResponse delete(@PathVariable String id,
			@RequestParam(name = "version", required = false) String version) {
		Long tradeId = parseTradeId(id);
		long expectedVersion = TradeRequestParser.parseVersionParam(version);
		TradeResult result = tradeService.deleteTrade(tradeId, expectedVersion);
		if (result.holdingDeleted()) {
			return new TradeWriteResponse(null, null, true);
		}
		PositionView view = positionQueryService.findByCode(result.holding().getCode());
		return new TradeWriteResponse(null, HoldingV2Response.from(view), false);
	}

	/** 寫入後重新讀一次：持股＋這筆交易（附重算的手續費、稅、已實現） */
	private TradeWriteResponse responseFor(String code, Long tradeId) {
		PositionView view = positionQueryService.findByCode(code);
		List<Trade> trades = view.trades();
		List<TradeLine> lines = view.position().lines();
		for (int i = 0; i < trades.size(); i++) {
			if (trades.get(i).getId().equals(tradeId)) {
				return new TradeWriteResponse(TradeV2Response.from(trades.get(i), lines.get(i)),
						HoldingV2Response.from(view), false);
			}
		}
		throw new TradeNotFoundException(tradeId); // 寫入後馬上被另一個裝置刪掉（極少見）
	}

	/** 網址的交易 id 不是數字（例如 /trades/abc）→ 當成找不到這筆交易 */
	private static Long parseTradeId(String id) {
		try {
			return Long.valueOf(id);
		} catch (NumberFormatException e) {
			throw new TradeNotFoundException(null);
		}
	}
}
