package io.qiao.exception;

/**
 * 同一個 Idempotency-Key 的前一個請求還在處理，等超過 3 秒（等鎖上限）還沒完成。2.0 第 6 題 C7。
 * 回 409（IETF Idempotency-Key 草案的建議），App 稍後用同一個 key 再送一次即可拿到結果。
 */
public class IdempotencyInProgressException extends RuntimeException {

	public static final String USER_MESSAGE = "這個請求還在處理中，請稍後再試（請用同一個 Idempotency-Key）";

	public IdempotencyInProgressException(Throwable cause) {
		super(USER_MESSAGE, cause);
	}
}
