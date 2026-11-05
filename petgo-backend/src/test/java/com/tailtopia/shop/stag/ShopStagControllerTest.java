package com.tailtopia.shop.stag;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * L1：v1.4.2 shop Story 4.1 AC8 —— 非 stag profile（ApiIntegrationTest 默认 dev）下取消退款 stag 入口不注册
 * （{@code @StagOnly}）。需 Docker postgres + redis，云端不跑。
 */
class ShopStagControllerTest extends ApiIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void notRegisteredOutsideStag() {
        assertThat(context.getBeansOfType(ShopStagController.class)).isEmpty();
    }
}
