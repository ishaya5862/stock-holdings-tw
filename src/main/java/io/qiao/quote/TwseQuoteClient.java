package io.qiao.quote;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.qiao.entity.Market;
import io.qiao.exception.QuoteUnavailableException;

/**
 * 臺灣證券交易所（上市）個股日成交資訊。
 *
 * 請求：GET {主機}/rwd/zh/afterTrading/STOCK_DAY?date=20261101&stockNo=2330&response=json
 * 回應（節錄）：
 * <pre>
 * 查到：  {"stat":"OK","title":"115年11月 2330 台積電           各日成交資訊",
 *          "fields":["日期",...,"收盤價",...],"data":[["115/11/02",...,"2,330.00",...]]}
 * 查無：  {"stat":"很抱歉，沒有符合條件的資料!","total":0}   ← 注意 HTTP 狀態碼仍是 200
 * </pre>
 */
public class TwseQuoteClient extends AbstractStockQuoteClient {

	private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE; // 20261101

	/** title 格式：「115年11月 2330 台積電           各日成交資訊」，取出代號後面、「各日成交資訊」前面的名稱 */
	private static final Pattern TITLE_NAME = Pattern.compile("^\\S+\\s+\\S+\\s+(.+?)\\s+各日成交資訊");

	public TwseQuoteClient(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
		super(restClient, objectMapper, clock);
	}

	@Override
	public Market market() {
		return Market.TWSE;
	}

	@Override
	protected MonthlyQuotes fetchMonth(String code, YearMonth month) {
		JsonNode root = getJson(uri -> uri
				.path("/rwd/zh/afterTrading/STOCK_DAY")
				.queryParam("date", month.atDay(1).format(DATE_PARAM))
				.queryParam("stockNo", code)
				.queryParam("response", "json")
				.build());

		String stat = root.path("stat").asText();
		if (stat.contains("沒有符合條件")) {
			// 這個月查無資料（代號不存在，或這個月還沒有交易資料）
			return MonthlyQuotes.empty();
		}
		if (!"OK".equalsIgnoreCase(stat)) {
			// 其他訊息（例如查詢太頻繁被擋）：無法判斷代號是否存在，不能當成「查無」
			throw new QuoteUnavailableException("TWSE 回應狀態異常：" + stat);
		}

		return parseSafely(() -> {
			JsonNode fields = root.get("fields");
			int dateColumn = QuoteParsing.columnIndex(fields, "日期");
			int closeColumn = QuoteParsing.columnIndex(fields, "收盤價", "收盤");
			return new MonthlyQuotes(
					parseName(root.path("title").asText(), code),
					QuoteParsing.dailyCloses(root.get("data"), dateColumn, closeColumn));
		});
	}

	/** 從 title 取出股票名稱；格式不如預期時退而用代號，避免整筆查詢失敗 */
	static String parseName(String title, String code) {
		Matcher matcher = TITLE_NAME.matcher(title.trim());
		return matcher.find() ? matcher.group(1).trim() : code;
	}
}
