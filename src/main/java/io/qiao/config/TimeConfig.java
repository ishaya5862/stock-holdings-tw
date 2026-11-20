package io.qiao.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 時間設定。
 *
 * 為什麼不直接用 LocalDateTime.now()？
 * 1. now() 會用「主機」的時區；正式機在海外，主機時區不是台北，顯示的時間和判斷的交易日都會錯。
 * 2. 統一由 Clock 取得時間，單元測試可以換成固定時間的 Clock（例如固定在 13:29、13:31 測試收盤前後的邏輯）。
 */
@Configuration
public class TimeConfig {

	@Bean
	public Clock clock(@Value("${app.zone}") String zone) {
		return Clock.system(ZoneId.of(zone));
	}
}
