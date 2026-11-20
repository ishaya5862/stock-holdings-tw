package io.qiao.entity;

/**
 * 市場別。名稱直接用 App 1.0 API 規格的值（TWSE / TPEX），存進資料庫也是這兩個字串。
 */
public enum Market {

	/** 上市（臺灣證券交易所） */
	TWSE("上市"),

	/** 上櫃（證券櫃檯買賣中心） */
	TPEX("上櫃");

	private final String label;

	Market(String label) {
		this.label = label;
	}

	/** 畫面上顯示的中文名稱 */
	public String getLabel() {
		return label;
	}
}
