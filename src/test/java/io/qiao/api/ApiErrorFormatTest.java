package io.qiao.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * API 錯誤格式與 JSON 嚴格設定的測試（第 5 題第 1 段）。
 *
 * 用「真的啟動伺服器」（RANDOM_PORT）＋ TestRestTemplate 送真的 HTTP 請求：
 * 不存在的網址會被轉到 /error 處理，這個轉送只有真的伺服器才會發生，MockMvc 模擬不出來。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiErrorFormatTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private ObjectMapper objectMapper;

	/** 模擬 App 1.0 的請求標頭（App 原始碼 Api.java） */
	private HttpEntity<Void> appRequest() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(List.of(MediaType.APPLICATION_JSON));
		headers.set(HttpHeaders.USER_AGENT, "HoldingsApp/1.0.0");
		return new HttpEntity<>(headers);
	}

	// ===== /api 底下不存在的網址 → 規格的錯誤格式（技術陷阱：不會經過任何 Controller） =====

	@Test
	void api不存在的網址_回404與規格錯誤格式_不帶程式細節() throws Exception {
		ResponseEntity<String> response = rest.exchange("/api/v1/no-such-api", HttpMethod.GET, appRequest(), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.path("error").path("code").asText()).isEqualTo("NOT_FOUND");
		assertThat(body.path("error").path("message").asText()).isEqualTo("找不到這個 API 路徑");
		// App 只認 error 物件；Spring 預設的欄位都不應該出現
		assertThat(body.has("timestamp")).isFalse();
		assertThat(body.has("trace")).isFalse();
		assertThat(response.getBody()).doesNotContain("Exception");
	}

	@Test
	void api不存在的網址_用POST也一樣是規格錯誤格式() throws Exception {
		ResponseEntity<String> response = rest.exchange("/api/v1/no-such-api", HttpMethod.POST, appRequest(), String.class);

		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.path("error").path("code").asText()).isNotEmpty();
		assertThat(body.path("error").path("message").asText()).isNotEmpty();
	}

	@Test
	void 不支援的HTTP方法_例如對總計送POST_回405與規格錯誤格式() throws Exception {
		ResponseEntity<String> response = rest.exchange("/api/v1/summary", HttpMethod.POST, appRequest(), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		JsonNode body = objectMapper.readTree(response.getBody());
		assertThat(body.path("error").path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
		assertThat(body.path("error").path("message").asText()).isNotEmpty();
	}

	@Test
	void 網頁不存在的網址_維持原本的錯誤處理_不受API格式影響() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(List.of(MediaType.TEXT_HTML));
		ResponseEntity<String> response = rest.exchange("/no-such-page", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).doesNotContain("\"error\":{");
		assertThat(response.getBody()).doesNotContain("Exception"); // 也不帶程式細節
	}

	// ===== JSON 嚴格設定：型別不對不能被自動轉換（陷阱 E3） =====

	/** 測試用：和之後 API 請求一樣的欄位型別 */
	record SampleRequest(Integer lots, @JsonProperty("buy_price") BigDecimal buyPrice) {
	}

	@Test
	void 張數送小數_不會被偷偷截成整數() {
		assertThatThrownBy(() -> objectMapper.readValue("{\"lots\":1.5}", SampleRequest.class))
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void 張數送字串_不會被自動轉成數字() {
		assertThatThrownBy(() -> objectMapper.readValue("{\"lots\":\"1\"}", SampleRequest.class))
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void 買進價送字串_不會被自動轉成數字() {
		assertThatThrownBy(() -> objectMapper.readValue("{\"buy_price\":\"980.5\"}", SampleRequest.class))
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void 正確的型別可以正常讀取_買進價保留原本的小數() throws Exception {
		SampleRequest request = objectMapper.readValue("{\"lots\":2,\"buy_price\":980.5}", SampleRequest.class);

		assertThat(request.lots()).isEqualTo(2);
		assertThat(request.buyPrice()).isEqualByComparingTo("980.5");
	}

	@Test
	void 金額不會輸出成科學記號() throws Exception {
		assertThat(objectMapper.writeValueAsString(new BigDecimal("1E+3"))).isEqualTo("1000");
	}
}
