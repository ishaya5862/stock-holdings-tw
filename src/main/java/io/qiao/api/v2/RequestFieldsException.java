package io.qiao.api.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2 請求的欄位格式錯誤（缺欄位、型別不對、日期格式錯、修改時帶了不能改的欄位）。第 6 題 C6-4 b。
 * 一次收集「所有」格式錯誤的欄位，回 400 VALIDATION_ERROR，fields 是「欄位名 → 訊息」。
 */
public class RequestFieldsException extends RuntimeException {

	private final transient Map<String, String> fields;

	public RequestFieldsException(Map<String, String> fields) {
		super(String.join("；", fields.values()));
		// 保留欄位的順序（LinkedHashMap），合併成一句的 message 才會每次一樣
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	public Map<String, String> fields() {
		return fields;
	}
}
