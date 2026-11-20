package io.qiao.calculation;

/**
 * 交易種類：買進或賣出（2.0 第 6 題）。
 * 放在 calculation 套件：計算核心不依賴資料庫；C2 的 trades 表 side 欄位（'BUY'／'SELL'）也沿用這個列舉。
 */
public enum TradeSide {
	/** 買進 */
	BUY,
	/** 賣出 */
	SELL
}
