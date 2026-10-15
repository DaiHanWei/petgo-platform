package com.tailtopia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// V1.3.4 Story 6.2 复审：src/test/resources 下没有测试专用配置（新建 application.yml 会整份遮住主配置），
// 故裸 @SpringBootTest 在这里逐类加同一属性 —— 与 ApiIntegrationTest 一致，测试期内运营通知扫描不自动跑，
// 免得把共享库里遗留的 PENDING 全量活动发给全部用户。
@SpringBootTest(properties = "petgo.ops-notify.scan-initial-delay-ms=86400000")
class TailtopiaBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
