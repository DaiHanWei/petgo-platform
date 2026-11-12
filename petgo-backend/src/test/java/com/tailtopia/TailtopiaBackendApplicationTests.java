package com.tailtopia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// V1.3.4 Story 6.2 复审：src/test/resources 下没有测试专用配置（新建 application.yml 会整份遮住主配置），
// 故裸 @SpringBootTest 在这里逐类加同一属性 —— 与 ApiIntegrationTest 一致，测试期内运营通知扫描不自动跑，
// 免得把共享库里遗留的 PENDING 全量活动发给全部用户。
// analytics-pipeline Story 1.1：埋点库（第二数据源）同 ApiIntegrationTest 指向埋点测试库、池压到 2（埋点 Flyway 至少要 2 条连接）。
@SpringBootTest(properties = {
        "petgo.ops-notify.scan-initial-delay-ms=86400000",
        "analytics.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${ANALYTICS_TEST_DB_NAME:petgo_analytics_test}",
        "analytics.datasource.hikari.maximum-pool-size=2",
        "analytics.datasource.hikari.minimum-idle=0",
        "analytics.datasource.hikari.idle-timeout=10000",
})
class TailtopiaBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
