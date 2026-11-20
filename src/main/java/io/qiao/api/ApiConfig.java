package io.qiao.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 相關設定。
 *
 * 註冊自己的 ErrorController 後，Spring Boot 就不會再建立預設的 BasicErrorController
 * （自動設定的條件是「還沒有 ErrorController 才建立」），/error 改由 ApiAwareErrorController 處理。
 */
@Configuration
public class ApiConfig {

	@Bean
	public ApiAwareErrorController apiAwareErrorController(ErrorAttributes errorAttributes,
			ServerProperties serverProperties, ObjectProvider<ErrorViewResolver> errorViewResolvers) {
		return new ApiAwareErrorController(errorAttributes, serverProperties.getError(),
				errorViewResolvers.orderedStream().toList());
	}
}
