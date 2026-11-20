package io.qiao.calculation;

import java.time.LocalDate;
import java.util.List;

/**
 * 賣超：某一筆賣出的股數超過「當時」持有的股數。
 *
 * 可能的情況（PARTB_NOTES C1 邊界清單）：第一筆就是賣出、同一天先輸入賣出後輸入買進、
 * 修改或刪除一筆舊的買進造成之後的賣出不成立、手機和電腦同時賣出。
 * 帶出「哪一筆」與「當時持有多少」，讓 API 與網頁組出清楚的錯誤訊息。
 *
 * 2.0（朋友測試回饋 2-1 a）：如果這檔有「日期是推估的、而且晚於這筆賣出」的買進
 * （1.0 轉入或 App 1.0 新增時沒有日期，用記帳日代替），很可能是推估日期太晚造成的，
 * 訊息會提示使用者先修改那筆買進的日期，並附上那幾筆買進（App 可以直接帶使用者去修改）。
 */
public class OversellException extends RuntimeException {

	private final transient TradeInput trade;
	private final long heldBefore;
	private final transient List<EstimatedBuy> estimatedBuys;

	public OversellException(TradeInput trade, long heldBefore) {
		this(trade, heldBefore, List.of());
	}

	private OversellException(TradeInput trade, long heldBefore, List<EstimatedBuy> estimatedBuys) {
		super(message(trade, heldBefore, estimatedBuys));
		this.trade = trade;
		this.heldBefore = heldBefore;
		this.estimatedBuys = List.copyOf(estimatedBuys);
	}

	/** 加上相關的推估日期買進（訊息會多一段提示） */
	public OversellException withEstimatedBuys(List<EstimatedBuy> buys) {
		return buys.isEmpty() ? this : new OversellException(trade, heldBefore, buys);
	}

	private static String message(TradeInput trade, long heldBefore, List<EstimatedBuy> estimatedBuys) {
		String base = trade.tradeDate() + " 賣出 " + trade.shares() + " 股，但當時只持有 " + heldBefore + " 股";
		if (estimatedBuys.isEmpty()) {
			return base;
		}
		String dates = String.join("、", estimatedBuys.stream().map(buy -> buy.tradeDate().toString()).toList());
		return base + "。這檔有 " + estimatedBuys.size() + " 筆買進的日期是推估的（1.0 沒有買進日期，用記帳日 " + dates
				+ " 代替），如果實際買進日期比較早，請先修改那筆買進的日期";
	}

	/** 造成賣超的那一筆賣出 */
	public TradeInput trade() {
		return trade;
	}

	/** 那一筆賣出之前持有的股數 */
	public long heldBefore() {
		return heldBefore;
	}

	/** 日期是推估的、而且晚於這筆賣出的買進；沒有則為空 */
	public List<EstimatedBuy> estimatedBuys() {
		return estimatedBuys;
	}

	/** 一筆推估日期的買進（App 修改它需要 id 與版本號） */
	public record EstimatedBuy(Long id, LocalDate tradeDate, Long version) {
	}
}
