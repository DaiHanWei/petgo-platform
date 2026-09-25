package com.tailtopia.admin.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * 仅 staging 环境注册的 bean / 控制器（V1.3.0 Story 2.2；与 B12 模拟回调（D-41）、Story 3.3 手动跑批共用同一门控）。
 * 元注解 {@code @Profile("stag")}：生产不存在这些 bean / 端点，而不是「存在但禁用」。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Profile("stag")
public @interface StagOnly {
}
