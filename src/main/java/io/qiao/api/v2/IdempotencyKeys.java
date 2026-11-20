package io.qiao.api.v2;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import io.qiao.service.IdempotencyRequest;

/**
 * v2 寫入請求的 Idempotency-Key：檢查格式、算出請求指紋（2.0 第 6 題 C7）。
 *
 * 規則（PARTB_NOTES B-27；參考 IETF draft-ietf-httpapi-idempotency-key-header-08，尚未成為 RFC）：
 * - 一律必帶（C7-1 b）：缺少 → 400 IDEMPOTENCY_KEY_REQUIRED（草案建議 400）
 * - 格式：1～64 個英數字、-、_，建議 UUID；接受草案寫法的雙引號 "…"；不合格 → 400 IDEMPOTENCY_KEY_INVALID
 * - 指紋：方法＋網址＋「解析後」的欄位算 SHA-256。不用原始 JSON 文字，
 *   因為多一個空格、欄位順序不同就會被誤判成「不同內容」。
 */
public final class IdempotencyKeys {

	static final String HEADER = "Idempotency-Key";
	static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private IdempotencyKeys() {
	}

	/** 檢查 key（缺少或格式不對就丟例外），回傳去掉雙引號後的 key */
	static String require(String header) {
		if (header == null || header.isBlank()) {
			throw new IdempotencyKeyException(true);
		}
		String key = header.trim();
		if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
			key = key.substring(1, key.length() - 1); // 草案的 Structured Field 字串寫法："8e03978e-…"
		}
		if (!FORMAT.matcher(key).matches()) {
			throw new IdempotencyKeyException(false);
		}
		return key;
	}

	/** 買進／賣出 */
	static IdempotencyRequest forCreate(String key, String code, TradeRequestParser.NewTrade request) {
		String path = "/api/v2/holdings/" + code + "/trades";
		return new IdempotencyRequest(key, hash("POST", path, request.side().name(), request.tradeDate().toString(),
				Long.toString(request.shares()), plain(request.price())), "POST", path, 201);
	}

	/** 修改一筆交易 */
	static IdempotencyRequest forUpdate(String key, Long tradeId, TradeRequestParser.TradeChange change) {
		String path = "/api/v2/trades/" + tradeId;
		return new IdempotencyRequest(key, hash("PUT", path, change.tradeDate().toString(),
				Long.toString(change.shares()), plain(change.price()), Long.toString(change.version())), "PUT", path, 200);
	}

	/** 刪除一筆交易 */
	static IdempotencyRequest forDelete(String key, Long tradeId, long version) {
		String path = "/api/v2/trades/" + tradeId;
		return new IdempotencyRequest(key, hash("DELETE", path, Long.toString(version)), "DELETE", path, 200);
	}

	/** 價格統一寫法：600、600.0、600.00 視為相同 */
	private static String plain(BigDecimal price) {
		return price.stripTrailingZeros().toPlainString();
	}

	private static String hash(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Java 一定支援 SHA-256", e);
		}
	}

	/** key 缺少或格式不對 → 400 */
	public static class IdempotencyKeyException extends RuntimeException {

		private final boolean missing;

		IdempotencyKeyException(boolean missing) {
			super(missing
					? "請在標頭 Idempotency-Key 帶一個唯一的識別碼（建議 UUID），重送時用同一個，才不會重複記錄"
					: "Idempotency-Key 格式不正確：請用 1～64 個英文字母、數字、- 或 _（建議 UUID）");
			this.missing = missing;
		}

		public boolean missing() {
			return missing;
		}
	}
}
