package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** L1（V1.4.2 shop-admin Story 3.1 AC3 / AC9）：新建排末尾、拖动重写权重 N..1 并写审计、编辑不动排序。需真库。 */
class AdminShopBannerReorderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShopBannerService service;
    @Autowired
    private ShopBannerRepository banners;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private ShopBannerForm form(String key) {
        ShopBannerForm f = new ShopBannerForm();
        f.setImageKey(key);
        f.setImageW(1056);
        f.setImageH(444);
        f.setTargetType("NONE");
        return f;
    }

    @Test
    void newBannerGoesLastAndReorderRewritesWeights() {
        long actor = adminAccounts.save(AdminAccount.newSuperAdmin("bnr-" + SEQ.incrementAndGet() + "@tailtopia.test",
                "banner", "{bcrypt}x")).getId();
        ShopBanner a = service.create(form("shop-banner/a.jpg"), actor);
        ShopBanner b = service.create(form("shop-banner/b.jpg"), actor);
        List<Long> order = banners.findAllByOrderBySortWeightDescIdDesc().stream().map(ShopBanner::getId).toList();
        assertThat(order.indexOf(b.getId())).isGreaterThan(order.indexOf(a.getId()));

        List<Long> reversed = new ArrayList<>(order);
        java.util.Collections.reverse(reversed);
        service.reorder(reversed, actor);
        assertThat(banners.findAllByOrderBySortWeightDescIdDesc().stream().map(ShopBanner::getId).toList())
                .isEqualTo(reversed);

        service.update(a.getId(), form("shop-banner/a2.jpg"), actor);
        assertThat(banners.findAllByOrderBySortWeightDescIdDesc().stream().map(ShopBanner::getId).toList())
                .as("编辑不改排序").isEqualTo(reversed);

        assertThatThrownBy(() -> service.reorder(List.of(a.getId()), actor)).isInstanceOf(AppException.class);
    }
}
