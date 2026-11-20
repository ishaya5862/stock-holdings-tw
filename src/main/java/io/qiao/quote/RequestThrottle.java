package io.qiao.quote;

import java.time.Duration;

/**
 * 控制「每一次請求之間至少間隔多久」，避免短時間大量查詢被證交所擋。
 *
 * 只用在背景排程：排程一輪建立一個，每次送出請求前呼叫 beforeRequest()。
 * 新增持股時用 NONE（不等待），才能在 10 秒內有結果。
 */
public class RequestThrottle {

	/** 不限制（新增持股時使用） */
	public static final RequestThrottle NONE = new RequestThrottle(Duration.ZERO);

	private final Duration gap;
	/** 上一次送出請求的時間（System.nanoTime），-1 表示還沒送過 */
	private long lastRequestNanos = -1;

	public RequestThrottle(Duration gap) {
		this.gap = gap;
	}

	/**
	 * 送出請求前呼叫：距離上一次請求不到 gap 就先等待。
	 * synchronized：同一個 throttle 被多條執行緒共用時，也能確保一次只放行一個請求。
	 */
	public synchronized void beforeRequest() {
		if (gap.isZero()) {
			return;
		}
		if (lastRequestNanos >= 0) {
			long waitNanos = lastRequestNanos + gap.toNanos() - System.nanoTime();
			if (waitNanos > 0) {
				try {
					Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
				} catch (InterruptedException e) {
					// 程式關閉時排程會被中斷：保留中斷狀態，讓呼叫端可以結束
					Thread.currentThread().interrupt();
				}
			}
		}
		lastRequestNanos = System.nanoTime();
	}
}
