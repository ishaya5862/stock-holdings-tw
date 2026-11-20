package io.qiao.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * 2.0 持股計算（第 6 題）：把一檔股票的全部交易依序重算，得到股數、平均成本、已實現與未實現損益。
 *
 * 規則（PARTB_NOTES B-1、B-8～B-10）：
 * | 排序       | 交易日期 → id（同一天依輸入順序）；還沒存檔的新交易（id = null）排在同一天最後            |
 * | 買進       | 剩餘成本 ＋（成交金額 ＋ 這筆的手續費）；每筆各自算手續費（每筆最低 20 元）               |
 * | 賣出       | 分攤成本 = 剩餘成本 × 賣出股數 ÷ 持有股數，四捨五入到分；全部賣出時 = 剩餘成本全部        |
 * |            | 已實現損益 = 成交金額 − 手續費 − 證交稅 − 分攤成本；剩餘成本 = 剩餘成本 − 分攤成本         |
 * | 賣超       | 任何一筆賣出超過當時持有股數 → OversellException                                          |
 * | 平均成本   | 剩餘成本 ÷ 股數，四捨五入到分，只用來顯示（不拿去算別的，避免捨入誤差）                   |
 * | 未實現     | 照舊沿用第 4 題：收盤價全部賣出 − 賣出手續費 − 證交稅 − 剩餘成本；收益率分母 = 剩餘成本  |
 *
 * 為什麼剩餘成本用「相減」而不是「股數 × 平均成本」：平均成本除不盡（例如 613.37125…），
 * 乘回去每次都會產生零頭；相減可以保證「買進成本合計 = 分攤合計 + 剩餘成本」一分不差，賣光時剛好歸零。
 *
 * 設計：
 * - 不碰資料庫、不取「現在時間」（海外主機時區不同）：評價日期由呼叫端用台北時區的 Clock 傳入。
 * - 手續費、證交稅規則沿用 ProfitCalculator（規則只有一份）。只有一筆買進時，結果和第 4 題完全相同（v1 數字不變）。
 * - 同樣的輸入一定得到同樣的結果；手機與電腦同時操作的正確性由服務層「鎖住持股後讀最新交易再重算」保證（C3）。
 */
@Component
public class PositionCalculator {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	/** 依交易日期 → id 排序；id 為 null（還沒存檔）排在同一天最後 */
	private static final Comparator<TradeInput> ORDER = Comparator
			.comparing(TradeInput::tradeDate)
			.thenComparing(TradeInput::id, Comparator.nullsLast(Comparator.naturalOrder()));

	private final ProfitCalculator profitCalculator;

	public PositionCalculator(ProfitCalculator profitCalculator) {
		this.profitCalculator = profitCalculator;
	}

	/**
	 * @param code          股票代號（決定證交稅率）
	 * @param trades        這一檔的全部交易（順序不拘，會重新排序）
	 * @param closePrice    收盤價；取不到時傳 null（未實現損益為 null，其他照算）
	 * @param valuationDate 評價日期（算未實現損益的證交稅用；呼叫端用台北時區的「今天」）
	 * @throws OversellException        任何一筆賣出超過當時持有的股數
	 * @throws IllegalArgumentException 股數或價格 ≤ 0、缺少買賣種類或日期
	 */
	public PositionResult calculate(String code, List<TradeInput> trades, BigDecimal closePrice, LocalDate valuationDate) {
		trades.forEach(PositionCalculator::validate);
		List<TradeInput> ordered = trades.stream().sorted(ORDER).toList();

		long shares = 0;
		BigDecimal remainingCost = zero();
		// 剩餘的買進金額（不含手續費）：只用來算 v1 的 buy_price（平均買進價），分攤方式和剩餘成本相同（C4-2）
		BigDecimal remainingAmount = zero();
		BigDecimal realizedPnl = zero();
		List<TradeLine> lines = new ArrayList<>(ordered.size());

		for (TradeInput trade : ordered) {
			BigDecimal amount = trade.price().multiply(BigDecimal.valueOf(trade.shares()));
			BigDecimal fee = profitCalculator.fee(amount);

			if (trade.side() == TradeSide.BUY) {
				shares += trade.shares();
				remainingCost = toCents(remainingCost.add(amount).add(fee));
				remainingAmount = toCents(remainingAmount.add(amount));
				lines.add(new TradeLine(trade, amount, fee, null, null, null, shares, remainingCost));
				continue;
			}

			// 賣出
			if (trade.shares() > shares) {
				throw new OversellException(trade, shares);
			}
			BigDecimal tax = profitCalculator.tax(code, amount, trade.tradeDate());
			BigDecimal costOfSold = allocate(remainingCost, trade.shares(), shares);
			BigDecimal amountOfSold = allocate(remainingAmount, trade.shares(), shares);
			BigDecimal realized = toCents(amount.subtract(fee).subtract(tax).subtract(costOfSold));

			shares -= trade.shares();
			remainingCost = toCents(remainingCost.subtract(costOfSold));
			remainingAmount = toCents(remainingAmount.subtract(amountOfSold));
			realizedPnl = realizedPnl.add(realized);
			lines.add(new TradeLine(trade, amount, fee, tax, costOfSold, realized, shares, remainingCost));
		}

		BigDecimal averageCost = shares == 0
				? null
				: remainingCost.divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
		BigDecimal averageBuyPrice = shares == 0
				? null
				: remainingAmount.divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
		Unrealized unrealized = (closePrice == null || shares == 0)
				? null
				: unrealized(code, shares, remainingCost, closePrice, valuationDate);

		return new PositionResult(shares, remainingCost, remainingAmount, averageCost, averageBuyPrice, realizedPnl,
				unrealized, List.copyOf(lines));
	}

	/**
	 * 賣出時分攤：全部賣出 → 剩下的全部（剛好歸零、不留零頭）；否則 剩餘 × 賣出股數 ÷ 持有股數，四捨五入到分。
	 * 成本（含手續費）與買進金額（不含手續費）用同一套分攤方式。
	 */
	private static BigDecimal allocate(BigDecimal remaining, long sold, long held) {
		return sold == held
				? remaining
				: remaining.multiply(BigDecimal.valueOf(sold)).divide(BigDecimal.valueOf(held), 2, RoundingMode.HALF_UP);
	}

	/** 未實現損益：照舊沿用第 4 題的公式，只是成本換成「剩餘成本」 */
	private Unrealized unrealized(String code, long shares, BigDecimal remainingCost, BigDecimal closePrice, LocalDate valuationDate) {
		BigDecimal marketValue = closePrice.multiply(BigDecimal.valueOf(shares));
		BigDecimal sellFee = profitCalculator.fee(marketValue);
		BigDecimal tax = profitCalculator.tax(code, marketValue, valuationDate);
		BigDecimal pnl = toCents(marketValue.subtract(sellFee).subtract(tax).subtract(remainingCost));
		// 先乘 100 再除，只捨入一次（和第 4 題相同）；剩餘成本在持有股數 > 0 時一定 > 0，不會除以 0
		BigDecimal returnPct = pnl.multiply(HUNDRED).divide(remainingCost, 2, RoundingMode.HALF_UP);
		return new Unrealized(marketValue, sellFee, tax, pnl, returnPct);
	}

	private static void validate(TradeInput trade) {
		Objects.requireNonNull(trade, "交易不能是 null");
		if (trade.side() == null) {
			throw new IllegalArgumentException("缺少買賣種類：" + trade);
		}
		if (trade.tradeDate() == null) {
			throw new IllegalArgumentException("缺少交易日期：" + trade);
		}
		if (trade.shares() <= 0) {
			throw new IllegalArgumentException("股數必須大於 0：" + trade);
		}
		if (trade.price() == null || trade.price().signum() <= 0) {
			throw new IllegalArgumentException("價格必須大於 0：" + trade);
		}
		// 價格最多兩位小數（v1、v2 的輸入驗證都會先擋）；這裡再確認，金額才能保證剛好到「分」
		if (trade.price().stripTrailingZeros().scale() > 2) {
			throw new IllegalArgumentException("價格最多兩位小數：" + trade);
		}
	}

	/** 金額統一保留到「分」（兩位小數）；成交金額本身最多兩位小數，手續費、稅都是整數元，這裡不會捨入到任何值 */
	private static BigDecimal toCents(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	private static BigDecimal zero() {
		return BigDecimal.ZERO.setScale(2);
	}
}
