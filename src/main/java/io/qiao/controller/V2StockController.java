package io.qiao.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.qiao.entity.Stock;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.quote.StockLookupService;
import io.qiao.quote.StockQuote;
import io.qiao.service.StockService;
import io.qiao.validation.StockCodes;

/**
 * 2.0 網頁的「查代號」（D-55）：買進表單的股票代號一離開輸入框就查，馬上顯示名稱與上市／上櫃，打錯可以立刻發現。
 *
 * 為什麼放在 /v2（網頁）而不是 /api/v2（App 的 API）：這是網頁自己用的小功能，不是給 App 2.0 的正式 API，
 * 不需要寫進 API.md、也不受「已發布的 API 只能新增、不能改」的限制。
 *
 * - 以前持有過的代號（股票表 stocks 有名稱與市場）：直接回答，不向官方查詢（快、不浪費官方的請求次數）
 * - 沒看過的代號：向證交所、櫃買中心確認（和送出買進時相同，最多約 9 秒）
 * - 回應格式：成功 {code, name, market}；失敗 {"error":{"code","message"}}（和 API 相同的錯誤格式）。
 *   錯誤在這裡自己轉成 JSON：網頁的例外處理（WebExceptionHandler）會把錯誤導回總覽，不適合給 JavaScript 讀。
 * - 這裡查不到不代表一定不能買：補登已下市股票的舊交易時，送出會改查「交易日期那個月」（TradeService），
 *   所以網頁只把「查無」顯示成提醒，不擋送出。
 */
@RestController
@RequestMapping("/v2/stocks")
public class V2StockController {

	private final StockService stockService;
	private final StockLookupService stockLookupService;

	public V2StockController(StockService stockService, StockLookupService stockLookupService) {
		this.stockService = stockService;
		this.stockLookupService = stockLookupService;
	}

	/** 查到的股票（網頁顯示用） */
	record StockInfo(String code, String name, String market) {
	}

	@GetMapping("/{code}")
	public ResponseEntity<?> lookup(@PathVariable String code) {
		String normalized = StockCodes.normalize(code);
		if (!StockCodes.isValid(normalized)) {
			return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", StockCodes.MESSAGE);
		}
		Stock known = stockService.findByCodes(List.of(normalized)).get(normalized);
		if (known != null && known.getName() != null && known.getMarket() != null) {
			return ResponseEntity.ok(new StockInfo(normalized, known.getName(), known.getMarket().name()));
		}
		try {
			StockQuote quote = stockLookupService.lookup(normalized);
			return ResponseEntity.ok(new StockInfo(quote.code(), quote.name(), quote.market().name()));
		} catch (StockNotFoundException e) {
			return error(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", e.getMessage());
		} catch (QuoteUnavailableException e) {
			return error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", QuoteUnavailableException.USER_MESSAGE);
		}
	}

	private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
	}
}
