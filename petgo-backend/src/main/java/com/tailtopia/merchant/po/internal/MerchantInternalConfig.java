package com.tailtopia.merchant.po.internal;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 进货内部接口的独立端口与守卫装配（契约 5.1，AD-4）。
 *
 * <p>独立端口用 Tomcat 附加 Connector 实现：同一个应用、同一套 Bean，只是多监听一个端口；
 * 端口分区由 {@link InternalApiGuardFilter} 按 {@code request.getLocalPort()} 判定。
 */
@Configuration
@EnableConfigurationProperties(MerchantInternalProperties.class)
public class MerchantInternalConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> merchantInternalConnector(
            MerchantInternalProperties props) {
        return factory -> {
            if (!props.enabled()) {
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(props.getPort());
            factory.addAdditionalConnectors(connector);
        };
    }

    /** 排在 Spring Security（order -100）之前：没过守卫的请求根本进不了任何 filter chain。 */
    @Bean
    public FilterRegistrationBean<InternalApiGuardFilter> internalApiGuardFilter(MerchantInternalProperties props) {
        FilterRegistrationBean<InternalApiGuardFilter> reg =
                new FilterRegistrationBean<>(new InternalApiGuardFilter(props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
