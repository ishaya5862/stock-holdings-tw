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
				PortfolioSummary.of(holdings.stream().map(HoldingView::toSummaryLine).toList()));
	}
}
