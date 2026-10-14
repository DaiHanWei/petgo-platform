package com.tailtopia.ops.target;

/**
 * 统一跳转目标类型（V1.3.4 AD-14 / AD-16）。运营弹窗与运营通知（Epic 6）共用。
 *
 * <p>落库 varchar + CHECK（{@code ck_ops_popups_target_type}），UPPER_SNAKE。
 * 🔴 <b>只在末尾追加，不重排 / 不删除 / 不改拼写</b>；加值须同步迁移 CHECK 与 App 端解析。
 */
public enum OpsTargetType {
    /** 无跳转：点击按钮只关闭。target_value 必为 NULL。 */
    NONE,
    /** 商品详情：value = 商品 public_token。 */
    PRODUCT,
    /** Toko 品类：value = {@code ProductCategory} 枚举名。 */
    CATEGORY,
    /** App 内固定页面：value = {@link OpsAppPage} 枚举名。 */
    APP_PAGE,
    /** 外部链接：value = http(s) URL。 */
    EXTERNAL
}
