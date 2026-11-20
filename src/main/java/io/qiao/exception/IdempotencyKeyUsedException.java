package io.qiao.exception;

/**
 * 這個 Idempotency-Key 已經被另一個請求用過（已完成，或剛好同時到達、對方先完成）。2.0 第 6 題 C7。
 * 不是錯誤：API 層收到後會讀出那筆紀錄，指紋相同就回傳結果（重送），不同就回 422。
 */
public class IdempotencyKeyUsedException extends RuntimeException {

	public IdempotencyKeyUsedException(String key) {
		super("Idempotency-Key 已使用：" + key);
	}
}
