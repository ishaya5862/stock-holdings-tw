package io.qiao.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

/**
 * API 相關設定。
 */
@Configuration
public class ApiConfig {

	/**
	 * 註冊自己的 ErrorController 後，Spring Boot 就不會再建立預設的 BasicErrorController
	 * （自動設定的條件是「還沒有 ErrorController 才建立」），/error 改由 ApiAwareErrorController 處理。
	 */
	@Bean
	public ApiAwareErrorController apiAwareErrorController(ErrorAttributes errorAttributes,
			ServerProperties serverProperties, ObjectProvider<ErrorViewResolver> errorViewResolvers) {
		return new ApiAwareErrorController(errorAttributes, serverProperties.getError(),
				errorViewResolvers.orderedStream().toList());
	}

	/**
	 * 文字欄位（例如股票代號）不接受 JSON 的數字、布林值。
	 *
	 * 為什麼需要（測試時發現）：application.properties 的 allow-coercion-of-scalars=false
	 * 只擋「字串 → 數字」，擋不到「數字 → 字串」。代號送成數字 0050 會變成文字 "50"（開頭的 0 不見），
	 * 之後被判成格式錯誤，訊息會誤導使用者。規格規定 code 是字串，這裡明確拒絕，回 400。
	 */
	@Bean
	public Jackson2ObjectMapperBuilderCustomizer strictTextualFields() {
		return builder -> builder.postConfigurer(objectMapper -> objectMapper.coercionConfigFor(LogicalType.Textual)
				.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}
}
