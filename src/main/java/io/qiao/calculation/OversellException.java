package io.qiao.calculation;

/**
 * 賣超：某一筆賣出的股數超過「當時」持有的股數。
 *
 * 可能的情況（PARTB_NOTES C1 邊界清單）：第一筆就是賣出、同一天先輸入賣出後輸入買進、
 * 修改或刪除一筆舊的買進造成之後的賣出不成立、手機和電腦同時賣出。
 * 帶出「哪一筆」與「當時持有多少」，讓 API 與網頁組出清楚的錯誤訊息。
 */
public class OversellException extends RuntimeException {

	private final transient TradeInput trade;
	private final long heldBefore;

	public OversellException(TradeInput trade, long heldBefore) {
		super(trade.tradeDate() + " 賣出 " + trade.shares() + " 股，但當時只持有 " + heldBefore + " 股");
		this.trade = trade;
		this.heldBefore = heldBefore;
	}

	/** 造成賣超的那一筆賣出 */
	public TradeInput trade() {
		return trade;
	}

	/** 那一筆賣出之前持有的股數 */
	public long heldBefore() {
		return heldBefore;
	}
}
