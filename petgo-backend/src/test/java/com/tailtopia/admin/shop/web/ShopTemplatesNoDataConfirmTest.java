package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 源码扫描（V1.4.2 shop-admin Story 1.2 · AD-3 AC3）：商城后台模板里不再出现原生 {@code data-confirm}
 * （含 {@code data-confirm-button} / {@code data-confirm-tpl}，注释里的字面量也不许留）——
 * 二次确认一律走服务端 confirm-modal（先 POST {@code …/confirm}）。
 */
class ShopTemplatesNoDataConfirmTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates", "admin");

    private static boolean isShopTemplate(Path p) {
        String rel = TEMPLATES.relativize(p).toString().replace('\\', '/');
        String name = p.getFileName().toString();
        return name.endsWith(".html") && (name.startsWith("shop-") || name.startsWith("merchant-purchase-order")
                || name.contains("-shop-") || rel.startsWith("fragments/shop-"));
    }

    @Test
    void shopTemplatesHaveNoNativeDataConfirm() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path p : files.filter(Files::isRegularFile).filter(ShopTemplatesNoDataConfirmTest::isShopTemplate).toList()) {
                scanned.add(p.getFileName().toString());
                String html = Files.readString(p, StandardCharsets.UTF_8);
                if (html.contains("data-confirm")) {
                    offenders.add(TEMPLATES.relativize(p).toString());
                }
            }
        }
        assertThat(scanned).as("扫描范围须含架构 §1.1 第 7 条的全部文件")
                .contains("shop-shipping.html", "merchant-purchase-order-detail.html", "shop-exception-panel.html",
                        "shop-banners-list.html", "shop-products-list.html", "drawer-shop-inventory.html",
                        "drawer-shop-order.html", "shop-return-panel.html");
        assertThat(offenders).as("商城模板仍有原生 data-confirm（改为 …/confirm 端点 + confirm-modal）").isEmpty();
    }
}
