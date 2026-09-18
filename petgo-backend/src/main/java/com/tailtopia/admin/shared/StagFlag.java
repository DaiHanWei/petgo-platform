package com.tailtopia.admin.shared;

import org.springframework.stereotype.Component;

/**
 * staging 标记 bean（V1.3.0 Story 2.2）：只在 {@code stag} profile 存在；{@code GlobalModelAdvice} 用
 * {@code Optional<StagFlag>} 注入模板 {@code ${stag}}，顶栏据此渲染黄色「STAG」角标，生产不渲染。
 */
@Component
@StagOnly
public class StagFlag {

    public boolean isStag() {
        return true;
    }
}
