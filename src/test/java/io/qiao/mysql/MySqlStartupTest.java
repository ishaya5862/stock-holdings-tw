package io.qiao.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 在真的 MySQL 上啟動：migration 與資料庫設定（Testcontainers 第 1 項，情境 #1、#7）。
 *
 * 程式能啟動本身就是一個檢查：設定是 ddl-auto=validate，Entity 和資料表的型別對不上就會啟動失敗。
 * 考試時 Hibernate 把 enum 對應成 MySQL 的 ENUM 型別（資料表是 VARCHAR）就是這樣抓到的，H2 檢查比較寬鬆、測不出來。
 */
class MySqlStartupTest extends MySqlTestBase {

	@Autowired
	private Flyway flyway;

	@Test
	void 連到的是真的MySQL8點4_不是H2() {
		// 先確認測試真的跑在 MySQL 上：設定寫錯時可能不知不覺仍連到 H2，後面的測試就失去意義
		String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
		assertThat(version).startsWith("8.4.");
	}

	@Test
	void 空的MySQL_所有migration都成功套用_沒有待套用的() {
		MigrationInfo[] applied = flyway.info().applied();

		assertThat(applied).isNotEmpty();
		assertThat(applied).allSatisfy(migration -> assertThat(migration.getState().isFailed()).isFalse());
		// 不寫死 V7：之後新增 V8 時，這個測試一樣會檢查 V8 能不能在 MySQL 上套用
		assertThat(flyway.info().pending()).isEmpty();
	}

	@Test
	void 每條連線的等鎖上限是3秒_不是MySQL預設的50秒() {
		// JdbcTemplate 從連線池拿連線，和程式用的是同一批連線（都有執行 connection-init-sql）
		Integer timeout = jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
		assertThat(timeout).isEqualTo(3);
	}
}
