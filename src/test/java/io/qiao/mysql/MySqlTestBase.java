package io.qiao.mysql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.qiao.quote.StockLookupService;

/**
 * 在「真的 MySQL」上跑的測試共用的設定（H2 測不出 MySQL 的鎖、交易隔離、死結）。
 *
 * - 測試開始時用 Docker 開一個全新的 mysql:8.4 容器（和 docker-compose.yml 同一個版本），測完自動刪除；
 *   名稱、埠號都由 Testcontainers 自動挑，不會碰到開發用的 stockholdingstw-mysql-1。
 * - 電腦沒有 Docker（或 Docker Desktop 沒開）時，繼承這個類別的測試都標成「略過（Skipped）」，其他 H2 測試照常跑，
 *   所以 README 的「mvnw test 不需要 Docker」仍然成立。注意：略過不會報錯，commit 前要確認 MySQL 測試的 Skipped 是 0。
 *
 * 為什麼容器用 @Bean 而不是 @Container 靜態欄位：
 *   @Container 會在「每個測試類別」結束時關掉容器，但 Spring 會把同樣設定的程式（ApplicationContext）留著給下一個類別用，
 *   下一個類別就會連到已經關掉的資料庫。交給 Spring 管理的 @Bean，容器和程式一起建立、一起關閉；
 *   所有 MySQL 測試的設定完全相同（都繼承這個類別、不另外加 @MockitoBean），所以整個測試過程只開一個容器。
 *
 * 資料不會自動復原：各測試類別在每個測試前呼叫 {@link #cleanDatabase()}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql") // 讀取 config/application-mysql.properties：等鎖上限改回 MySQL 的寫法
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestBase.MySqlContainerConfig.class)
public abstract class MySqlTestBase {

	/** 測試不可連證交所、櫃買中心：查官方的部分換成假的（各測試自己設定回傳什麼） */
	@MockitoBean
	protected StockLookupService stockLookupService;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@TestConfiguration(proxyBeanMethods = false)
	static class MySqlContainerConfig {

		/** @ServiceConnection：自動把容器的網址、帳號、密碼交給 Spring Boot（取代測試設定裡的 H2 網址） */
		@Bean
		@ServiceConnection
		@SuppressWarnings("resource") // 不是忘記關閉：Spring 關閉程式時會自動呼叫容器的 close()
		MySQLContainer<?> mysqlContainer() {
			return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
					// 和 docker-compose.yml 一樣把資料庫時區設成台北（程式本身不依賴它，時間都由程式以台北時區產生）
					.withEnv("TZ", "Asia/Taipei");
		}
	}

	/** 清空資料（外鍵順序：先刪參照別人的表）；Flyway 的紀錄表不動 */
	protected void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM idempotency_keys");
		jdbcTemplate.update("DELETE FROM trades");
		jdbcTemplate.update("DELETE FROM holdings");
		jdbcTemplate.update("DELETE FROM stocks");
	}
}
