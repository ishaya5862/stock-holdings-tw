package io.qiao.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V5、V6（第 6 題 C2、C4）遷移測試：用「1.0 留下的資料庫」升級到 2.0，舊持股要正確轉成買進紀錄，舊欄位最後刪除。
 *
 * 做法：每個測試開一個獨立的 H2 記憶體資料庫（MySQL 相容模式，和其他測試相同），
 * Flyway 先只跑到 V4（= v1.1.0 的資料庫）→ 塞入 1.0 的資料 → 再跑到最新版 → 檢查。
 * 不啟動 Spring（純 Flyway＋JDBC），也不影響其他測試用的資料庫。
 *
 * H2 測不到 MySQL 特有的問題（例如布林欄位型別），另外用真的 MySQL 手動驗證（C2-7 決定兩種都做）。
 */
class V5MigrationTest {

	private String url;

	@BeforeEach
	void setUp() {
		// 每個測試一個新的資料庫名稱；DB_CLOSE_DELAY=-1：連線關閉後資料庫仍保留到 JVM 結束
		url = "jdbc:h2:mem:v5-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
	}

	@Test
	void 一點零的每筆持股轉成一筆買進_股數是張數乘以一千_日期取建立時間並標記推估() throws SQLException {
		migrateTo("4");
		execute("INSERT INTO stocks (code, name, market, updated_at) VALUES ('2330', '台積電', 'TWSE', '2026-10-15 09:00:00')",
				"INSERT INTO stocks (code, name, market, updated_at) VALUES ('0050', '元大台灣50', 'TWSE', '2026-10-15 09:00:00')",
				// 回報一的使用者：2025/5 買、2026/10 才記進 1.0 → 轉入日期是記錄那天（推估），不是真正的買進日
				"INSERT INTO holdings (id, code, lots, buy_price, created_at, updated_at) VALUES (12, '0050', 2, 180.00, '2026-10-15 23:59:59', '2026-10-20 10:00:00')",
				"INSERT INTO holdings (id, code, lots, buy_price, created_at, updated_at) VALUES (7, '2330', 999, 1066.67, '2026-11-01 00:00:00', '2026-11-01 00:00:00')");

		migrateTo("5"); // 只跑 V5（V6 會刪掉舊欄位，另一個測試專門測）

		try (Connection connection = connect(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT holding_id, side, trade_date, date_estimated, shares, price, version, created_at, updated_at FROM trades ORDER BY holding_id")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getLong("holding_id")).isEqualTo(7);
			assertThat(rs.getLong("shares")).isEqualTo(999_000); // 1.0 上限 999 張也不會溢位
			assertThat(rs.getBigDecimal("price")).isEqualByComparingTo("1066.67");
			assertThat(rs.getDate("trade_date").toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 1)); // 00:00:00 不會變成前一天

			assertThat(rs.next()).isTrue();
			assertThat(rs.getLong("holding_id")).isEqualTo(12); // App 1.0 記住的 id 不變
			assertThat(rs.getString("side")).isEqualTo("BUY");
			assertThat(rs.getDate("trade_date").toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 15)); // 23:59:59 不會變成隔天
			assertThat(rs.getBoolean("date_estimated")).isTrue();
			assertThat(rs.getLong("shares")).isEqualTo(2000);
			assertThat(rs.getBigDecimal("price")).isEqualByComparingTo("180.00");
			assertThat(rs.getLong("version")).isZero();
			assertThat(rs.getTimestamp("created_at").toLocalDateTime()).isEqualTo("2026-10-15T23:59:59");
			assertThat(rs.getTimestamp("updated_at").toLocalDateTime()).isEqualTo("2026-10-20T10:00:00");

			assertThat(rs.next()).isFalse(); // 一筆持股剛好一筆交易
		}

		// 持股加上版本號，預設 0；舊欄位（lots、buy_price）V5 先保留（擴充 → 遷移 → 收縮），V6 才刪除
		assertThat(queryLong("SELECT version FROM holdings WHERE id = 12")).isZero();
		assertThat(queryLong("SELECT lots FROM holdings WHERE id = 12")).isEqualTo(2);
	}

	@Test
	void 一點零升到最新版_V6刪掉舊欄位_交易紀錄完整保留() throws SQLException {
		// 第 8 題的情境：停掉 1.0、用 1.0 留下的資料庫（V4）直接啟動 2.0（一次跑完 V5、V6）
		migrateTo("4");
		execute("INSERT INTO stocks (code, updated_at) VALUES ('2330', '2026-11-20 09:00:00')",
				"INSERT INTO holdings (id, code, lots, buy_price, created_at, updated_at) VALUES (7, '2330', 3, 980.50, '2026-11-20 09:00:00', '2026-11-20 09:00:00')");

		migrateTo(null);

		assertThat(queryLong("SELECT shares FROM trades WHERE holding_id = 7")).isEqualTo(3000);
		assertThat(queryLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'holdings' AND column_name IN ('lots', 'buy_price')"))
				.as("V6 刪除 holdings.lots、buy_price").isZero();
	}

	@Test
	void 全新安裝沒有舊資料_也能升級完成() throws SQLException {
		migrateTo(null);

		assertThat(queryLong("SELECT COUNT(*) FROM trades")).isZero();
	}

	@Test
	void 資料庫限制_股數價格要大於零_種類只能買賣_日期不能早於1990() throws SQLException {
		migrateTo(null);
		execute("INSERT INTO stocks (code, updated_at) VALUES ('2330', '2026-11-20 09:00:00')",
				"INSERT INTO holdings (id, code, created_at, updated_at) VALUES (1, '2330', '2026-11-20 09:00:00', '2026-11-20 09:00:00')");

		assertThatThrownBy(() -> insertTrade("BUY", "2026-11-20", 0, "10.00")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertTrade("BUY", "2026-11-20", 100, "0")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertTrade("HOLD", "2026-11-20", 100, "10.00")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertTrade("BUY", "1989-12-31", 100, "10.00")).isInstanceOf(SQLException.class);

		insertTrade("SELL", "1990-01-01", 100, "10.00"); // 邊界值可以
		assertThat(queryLong("SELECT COUNT(*) FROM trades")).isEqualTo(1);
	}

	@Test
	void 有交易紀錄的持股_資料庫不允許直接刪除_要先刪交易() throws SQLException {
		// C2-2：外鍵不連帶刪除，避免誤刪持股就默默清掉交易歷史
		migrateTo(null);
		execute("INSERT INTO stocks (code, updated_at) VALUES ('2330', '2026-11-20 09:00:00')",
				"INSERT INTO holdings (id, code, created_at, updated_at) VALUES (1, '2330', '2026-11-20 09:00:00', '2026-11-20 09:00:00')");
		insertTrade("BUY", "2026-11-20", 1000, "10.00");

		assertThatThrownBy(() -> execute("DELETE FROM holdings WHERE id = 1")).isInstanceOf(SQLException.class);

		execute("DELETE FROM trades WHERE holding_id = 1", "DELETE FROM holdings WHERE id = 1");
		assertThat(queryLong("SELECT COUNT(*) FROM holdings")).isZero();
	}

	// ===== 工具 =====

	/** @param target 跑到哪一版（null = 最新版） */
	private void migrateTo(String target) {
		var config = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
		if (target != null) {
			config.target(target);
		}
		config.load().migrate();
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(url, "sa", "");
	}

	private void execute(String... sqls) throws SQLException {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			for (String sql : sqls) {
				statement.execute(sql);
			}
		}
	}

	private void insertTrade(String side, String date, long shares, String price) throws SQLException {
		execute("INSERT INTO trades (holding_id, side, trade_date, shares, price, created_at, updated_at) VALUES (1, '"
				+ side + "', '" + date + "', " + shares + ", " + price + ", '2026-11-20 09:00:00', '2026-11-20 09:00:00')");
	}

	private long queryLong(String sql) throws SQLException {
		try (Connection connection = connect(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
