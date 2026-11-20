package io.qiao.dto;

import java.util.List;

import io.qiao.calculation.PortfolioSummary;

/**
 * 持股清單頁需要的全部資料：每一筆持股 + 頁面最上方的總計。
 * 兩者用同一份快取資料一起算出來，確保清單和總計的數字一致。
 */
public record HoldingListView(List<HoldingView> holdings, PortfolioSummary summary) {

	public static HoldingListView of(List<HoldingView> holdings) {
		return new HoldingListView(holdings,
				// 資料異常的持股算不出金額，不算進總計（朋友回饋 1-1 a）
				PortfolioSummary.of(holdings.stream().filter(view -> !view.hasDataError())
						.map(HoldingView::toSummaryLine).toList()));
	}
}
