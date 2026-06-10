package com.petgo.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 文档元信息（springdoc）。
 * api-docs 路径与 3.1 版本在 application.yml 的 {@code springdoc.*} 配置。不引入 HATEOAS。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI petgoOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("PetGo API")
                .version("v1")
                .description("PetGo V1 后端 API（Spring Boot 4 / OpenAPI 3.1）"));
    }
}
