package io.qiao.api.v2;

/**
 * 同一個 Idempotency-Key 用在「不同內容」或「不同的 API」上（2.0 第 6 題 C7，B-27）。
 * 422（IETF Idempotency-Key 草案的建議）：照樣回第一次的結果，使用者會以為第二筆也成功了；
 * 當成新請求執行，又失去防重送的意義 → 明確告訴 App 這是用戶端的錯誤（每個不同的操作要用新的 key）。
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("這個 Idempotency-Key 已經用在另一個不同的請求上；每一個新的操作請產生新的 key，重送時才沿用同一個");
	}
}
