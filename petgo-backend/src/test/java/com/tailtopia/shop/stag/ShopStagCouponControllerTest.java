package com.tailtopia.shop.stag;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * L1：v1.4.2 shop Story 2.1 AC9 —— 非 stag profile（ApiIntegrationTest 默认 dev）下 stag 入口不注册
 * （{@code @StagOnly}，生产路由根本不存在）。需 Docker postgres + redis，云端不跑。
 */
class ShopStagCouponControllerTest extends ApiIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void notRegisteredOutsideStag() {
        assertThat(context.getBeansOfType(ShopStagCouponController.class)).isEmpty();
    }
}
