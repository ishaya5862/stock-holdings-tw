package io.qiao.api.v2;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.qiao.calculation.TradeSide;
import io.qiao.entity.IdempotencyRecord;
import io.qiao.entity.Trade;
import io.qiao.exception.IdempotencyInProgressException;
import io.qiao.exception.IdempotencyKeyUsedException;
import io.qiao.exception.NotHeldException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.exception.TradeNotFoundException;
import io.qiao.service.IdempotencyGuard;
import io.qiao.service.IdempotencyRequest;
import io.qiao.service.PositionQueryService;
import io.qiao.service.PositionQueryService.PositionView;
import io.qiao.service.TradeResult;
import io.qiao.service.TradeService;
import io.qiao.validation.StockCodes;

/**
 * App 2.0 API 的寫入（第 6 題 C6）：買進、賣出、修改一筆交易、刪除一筆交易。
 *
 * | 方法   | 路徑                            | 成功 | 失敗                                                                     |
 * | POST   | /api/v2/holdings/{code}/trades  | 201  | 400 VALIDATION_ERROR／IDEMPOTENCY_KEY_*、404 STOCK_NOT_FOUND／NOT_HELD、409 OVERSELL／HOLDING_BUSY／IDEMPOTENCY_KEY_IN_PROGRESS、422 IDEMPOTENCY_KEY_REUSED、503 QUOTE_UNAVAILABLE |
 * | PUT    | /api/v2/trades/{id}             | 200  | 400、404 TRADE_NOT_FOUND、409 VERSION_CONFLICT／OVERSELL／HOLDING_BUSY、422 |
 * | DELETE | /api/v2/trades/{id}?version=N   | 200  | 400、404 TRADE_NOT_FOUND、409 VERSION_CONFLICT／OVERSELL／HOLDING_BUSY、422 |
 *
 * 防止重送（C7）：每個寫入一律要帶 Idempotency-Key 標頭（C7-1 b）。
 * - 第一次：照常執行；key 和交易在同一個資料庫交易寫入（C7-2 a，TradeService）。
 * - 重送（同 key、同內容）：不再執行，回傳那次的狀態碼與「目前」的交易、持股，並加標頭 Idempotent-Replayed: true。
 * - 同 key、不同內容（或不同 API）：422 IDEMPOTENCY_KEY_REUSED（IETF 草案建議）。
 * - 同 key 的前一個請求還在處理、等超過 3 秒：409 IDEMPOTENCY_KEY_IN_PROGRESS（IETF 草案建議）。
 * 第一次失敗（例如賣超、官方連不上）不會留下 key，重送時重新執行。
 *
 * 規則與鎖都在 TradeService（C3）；這裡只負責「檢查請求格式 → 呼叫服務 → 轉成回應」。
 */
@RestController
@RequestMapping("/api/v2")
public class ApiV2TradeController {

	private final TradeService tradeService;
	private final PositionQueryService positionQueryService;
	private final IdempotencyGuard idempotencyGuard;

	public ApiV2TradeController(TradeService tradeService, PositionQueryService positionQueryService,
			IdempotencyGuard idempotencyGuard) {
		this.tradeService = tradeService;
		this.positionQueryService = positionQueryService;
		this.idempotencyGuard = idempotencyGuard;
	}

	/** 買進或賣出（內容的 side 決定，C6-1 a）。買進還沒持有的代號會先向官方確認代號存在 */
	@PostMapping("/holdings/{code}/trades")
	public ResponseEntity<TradeWriteResponse> create(@PathVariable String code,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String keyHeader,
			@RequestBody(required = false) JsonNode body) {
		String key = IdempotencyKeys.require(keyHeader);
		TradeRequestParser.NewTrade request = TradeRequestParser.parseNew(body);
		String normalizedCode = StockCodes.normalize(code);
		IdempotencyRequest idempotency = IdempotencyKeys.forCreate(key, normalizedCode, request);

		return executeOnce(idempotency, () -> {
			TradeResult result;
			if (request.side() == TradeSide.BUY) {
				// 代號格式不可能存在（例如 ABC、23）→ 直接 404，不浪費一次官方查詢（和 v1 相同）
				if (!StockCodes.isValid(normalizedCode)) {
					throw new StockNotFoundException(normalizedCode);
				}
				result = tradeService.buy(normalizedCode, request.tradeDate(), request.shares(), request.price(),
						idempotency);
			} else {
				result = tradeService.sell(normalizedCode, request.tradeDate(), request.shares(), request.price(),
						idempotency);
			}
			Long tradeId = result.trade().getId();
			return ResponseEntity.created(URI.create("/api/v2/trades/" + tradeId))
					.body(responseFor(result.holding().getCode(), tradeId));
		});
	}

	/** 修改一筆交易的日期、股數、價格（內容要帶 version，C6-2 b、C6-7 a） */
	@PutMapping("/trades/{id}")
	public ResponseEntity<TradeWriteResponse> update(@PathVariable String id,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String keyHeader,
			@RequestBody(required = false) JsonNode body) {
		String key = IdempotencyKeys.require(keyHeader);
		Long tradeId = parseTradeId(id);
		TradeRequestParser.TradeChange change = TradeRequestParser.parseChange(body);
		IdempotencyRequest idempotency = IdempotencyKeys.forUpdate(key, tradeId, change);

		return executeOnce(idempotency, () -> {
			TradeResult result = tradeService.updateTrade(tradeId, change.version(), change.tradeDate(),
					change.shares(), change.price(), idempotency);
			return ResponseEntity.ok(responseFor(result.holding().getCode(), tradeId));
		});
	}

	/** 刪除一筆交易（網址參數 ?version=，C6-2 b）。刪掉最後一筆時持股也一起刪除（C2-1） */
	@DeleteMapping("/trades/{id}")
	public ResponseEntity<TradeWriteResponse> delete(@PathVariable String id,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String keyHeader,
			@RequestParam(name = "version", required = false) String version) {
		String key = IdempotencyKeys.require(keyHeader);
		Long tradeId = parseTradeId(id);
		long expectedVersion = TradeRequestParser.parseVersionParam(version);
		IdempotencyRequest idempotency = IdempotencyKeys.forDelete(key, tradeId, expectedVersion);

		return executeOnce(idempotency, () -> {
			TradeResult result = tradeService.deleteTrade(tradeId, expectedVersion, idempotency);
			if (result.holdingDeleted()) {
				return ResponseEntity.ok(new TradeWriteResponse(null, null, true));
			}
			return ResponseEntity.ok(new TradeWriteResponse(null, holdingOrNull(result.holding().getCode()), false));
		});
	}

	// ===== 防止重送（C7） =====

	/**
	 * key 已經用過 → 重送（回傳那次的結果）或同 key 不同內容（422）；沒用過 → 執行。
	 * 先查一次（已完成的重送最常見，不必進到寫入）；執行時剛好撞到同時到達的相同請求，再查一次。
	 */
	private ResponseEntity<TradeWriteResponse> executeOnce(IdempotencyRequest idempotency,
			Supplier<ResponseEntity<TradeWriteResponse>> firstTime) {
		Optional<ResponseEntity<TradeWriteResponse>> replay = replayIfUsed(idempotency);
		if (replay.isPresent()) {
			return replay.get();
		}
		try {
			return firstTime.get();
		} catch (IdempotencyKeyUsedException e) {
			// 同一個 key 剛好同時到達、對方先完成 → 現在一定查得到
			return replayIfUsed(idempotency).orElseThrow(() -> new IdempotencyInProgressException(e));
		}
	}

	private Optional<ResponseEntity<TradeWriteResponse>> replayIfUsed(IdempotencyRequest idempotency) {
		Optional<IdempotencyRecord> found = idempotencyGuard.find(idempotency.key());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		IdempotencyRecord record = found.get();
		if (!record.getRequestHash().equals(idempotency.requestHash())) {
			throw new IdempotencyKeyReusedException(); // 同 key 不同內容（或不同 API）→ 422
		}
		return Optional.of(replay(record));
	}

	/**
	 * 重送的回應：那次的狀態碼＋「目前」的交易與持股（C7-2 a：key 只記住寫了哪一筆，不存回應原文）。
	 * 中間如果另一個裝置把那筆交易或持股刪了，對應的欄位就是 null。
	 */
	private ResponseEntity<TradeWriteResponse> replay(IdempotencyRecord record) {
		TradeWriteResponse body;
		if (record.isHoldingDeleted()) {
			body = new TradeWriteResponse(null, null, true);
		} else if (record.getTradeId() == null) {
			body = new TradeWriteResponse(null, holdingOrNull(record.getHoldingCode()), false); // 刪除一筆（不是最後一筆）
		} else {
			body = currentState(record.getHoldingCode(), record.getTradeId());
		}
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(record.getResponseStatus())
				.header(IdempotencyKeys.REPLAYED_HEADER, "true");
		if (record.getResponseStatus() == 201 && record.getTradeId() != null) {
			builder.location(URI.create("/api/v2/trades/" + record.getTradeId()));
		}
		return builder.body(body);
	}

	private TradeWriteResponse currentState(String code, Long tradeId) {
		try {
			return responseFor(code, tradeId);
		} catch (NotHeldException | TradeNotFoundException e) {
			return new TradeWriteResponse(null, holdingOrNull(code), false);
		}
	}

	// ===== 共用 =====

	/** 寫入後重新讀一次：持股＋這筆交易（附重算的手續費、稅、已實現） */
	private TradeWriteResponse responseFor(String code, Long tradeId) {
		PositionView view = positionQueryService.findByCode(code);
		List<Trade> trades = view.trades();
		for (int i = 0; i < trades.size(); i++) {
			if (trades.get(i).getId().equals(tradeId)) {
				// 寫入前已整條驗證過，正常不會資料異常；萬一是（例如寫入後另一個裝置剛好改壞），只回原始交易
				TradeV2Response trade = view.hasDataError()
						? TradeV2Response.raw(trades.get(i))
						: TradeV2Response.from(trades.get(i), view.position().lines().get(i));
				return new TradeWriteResponse(trade, HoldingV2Response.from(view), false);
			}
		}
		throw new TradeNotFoundException(tradeId); // 寫入後馬上被另一個裝置刪掉（極少見）
	}

	/** 目前的持股；已經不存在（例如另一個裝置刪掉了最後一筆交易）→ null */
	private HoldingV2Response holdingOrNull(String code) {
		try {
			return HoldingV2Response.from(positionQueryService.findByCode(code));
		} catch (NotHeldException e) {
			return null;
		}
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
