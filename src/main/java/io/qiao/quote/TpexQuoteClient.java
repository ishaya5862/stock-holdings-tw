package io.qiao.quote;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;

/**
 * 證券櫃檯買賣中心（上櫃）個股日成交資訊。
 *
 * 請求：GET {主機}/www/zh-tw/afterTrading/tradingStock?code=6488&date=2026/11/01&response=json
 * 回應（節錄）：
 * <pre>
 * 查到：  {"stat":"ok","name":"環球晶",
 *          "tables":[{"fields":["日 期",...,"收盤",...],"data":[["115/11/02",...,"760.00",...]]}]}
 * 查無：  {"stat":"ok","name":"","tables":[{"data":[],...}]}   ← stat 一樣是 ok，要看 data 是否為空
 * </pre>
 */
public class TpexQuoteClient extends AbstractStockQuoteClient {

	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ofPattern("yyyy/MM/dd"); // 2026/11/01

	public TpexQuoteClient(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
		super(restClient, objectMapper, clock);
	}

	@Override
	public Market market() {
		return Market.TPEX;
	}

	@Override
	protected MonthlyQuotes fetchMonth(String code, YearMonth month) {
		JsonNode root = getJson(uri -> uri
				.path("/www/zh-tw/afterTrading/tradingStock")
				.queryParam("code", code)
				.queryParam("date", month.atDay(1).format(DATE_PARAM))
				.queryParam("response", "json")
				.build());

		String stat = root.path("stat").asText();
		if (!"ok".equalsIgnoreCase(stat)) {
			throw new QuoteUnavailableException("TPEX 回應狀態異常：" + stat);
		}

		JsonNode table = root.path("tables").path(0);
		JsonNode data = table.path("data");
		if (!data.isArray() || data.isEmpty()) {
			// stat 是 ok 但 data 是空陣列 → 這個月查無資料
			return MonthlyQuotes.empty();
		}

		return parseSafely(() -> {
			JsonNode fields = table.get("fields");
			int dateColumn = QuoteParsing.columnIndex(fields, "日期");
			int closeColumn = QuoteParsing.columnIndex(fields, "收盤", "收盤價");
			String name = root.path("name").asText();
			return new MonthlyQuotes(
					name.isBlank() ? code : name.trim(),
					QuoteParsing.dailyCloses(data, dateColumn, closeColumn));
		});
	}
}
