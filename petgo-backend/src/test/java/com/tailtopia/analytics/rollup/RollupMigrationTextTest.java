package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** L0：Story 4.1 / 4.3 / 4.4 / 4.5 / 4.6 埋点库迁移的文本约束（授权白名单写法、无外键、视图、请求表不授权）。 */
class RollupMigrationTextTest {

    private static final String PATH = "db/analytics-migration/V20261113_1300__rollup_framework_activity_install.sql";

    private static final String PATH_43 = "db/analytics-migration/V20261113_1320__cohort_retention_and_install_profile.sql";

    private static final String PATH_44 = "db/analytics-migration/V20261113_1330__placement_and_push.sql";

    private static final String PATH_45 = "db/analytics-migration/V20261113_1400__agg_toko.sql";

    private static final String PATH_46 = "db/analytics-migration/V20261113_1410__agg_cart_product.sql";

    private static String sql() throws IOException {
        return read(PATH);
    }

    private static String read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void createsTablesAndView() throws IOException {
        String s = sql();
        assertThat(s).contains("CREATE TABLE agg_progress", "CREATE TABLE agg_daily_activity",
                "CREATE TABLE agg_daily_install", "CREATE VIEW v_events_person", "CREATE TABLE rollup_rerun_requests");
        assertThat(s).contains("PRIMARY KEY (stat_date, platform)");
        assertThat(s).contains("COMMENT ON COLUMN agg_daily_activity.stat_date IS 'WIB 日期");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
    }

    @Test
    void grantsViaReaderRolePlaceholderOnly() throws IOException {
        String s = sql();
        assertThat(s).contains("rolname = '${readerRole}'");
        for (String t : new String[] {"agg_progress", "agg_daily_activity", "agg_daily_install", "v_events_person"}) {
            assertThat(s).contains("format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
        }
        assertThat(s).doesNotContain("'rollup_rerun_requests', '${readerRole}'");
        assertThat(s).doesNotContain("TO analytics_reader");
    }

    @Test
    void viewImplementsD10() throws IOException {
        String s = sql();
        assertThat(s).contains("l.linked_at <= d.event_time", "ORDER BY l.linked_at DESC LIMIT 1",
                "l.linked_at > d.event_time", "ORDER BY l.linked_at ASC LIMIT 1", "'a:' || d.anonymous_id");
    }

    /** Story 4.3：组表与月表的列、约束、空值注释与授权。 */
    @Test
    void cohortRetentionAndInstallProfile() throws IOException {
        String s = read(PATH_43);
        assertThat(s).contains("CREATE TABLE agg_cohort_retention", "CREATE TABLE agg_monthly_install_profile");
        assertThat(s).contains("PRIMARY KEY (cohort_type, cohort_date)",
                "CHECK (cohort_type IN ('INSTALL', 'ONBOARDING'))",
                "CHECK (stat_month = date_trunc('month', stat_month)::date)");
        for (String c : new String[] {"d1_retained", "d3_retained", "d7_retained", "w1_retained"}) {
            assertThat(s).containsPattern("COMMENT ON COLUMN agg_cohort_retention\\." + c + " IS '[^']*空 = 未满");
        }
        for (String t : new String[] {"agg_cohort_retention", "agg_monthly_install_profile"}) {
            assertThat(s).contains("format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
        }
        assertThat(s).contains("rolname = '${readerRole}'").doesNotContain("TO analytics_reader");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
        // 汇总表不含可识别个人的列
        assertThat(s).doesNotContain("user_id", "anonymous_id", "person_key");
    }

    /** Story 4.4：运营位与推送的日 / 周 / 月表、主键、周一 / 月初约束、口径注释与授权；不含可识别个人的列。 */
    @Test
    void placementAndPush() throws IOException {
        String s = read(PATH_44);
        String[] tables = {"agg_daily_placement", "agg_weekly_placement", "agg_monthly_placement", "agg_daily_push",
            "agg_weekly_push", "agg_monthly_push"};
        for (String t : tables) {
            assertThat(s).contains("CREATE TABLE " + t + " (", "COMMENT ON TABLE " + t + " IS",
                    "format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
        }
        assertThat(s).contains("PRIMARY KEY (stat_date, placement_type, placement_token)",
                "PRIMARY KEY (period_start, placement_type, placement_token)",
                "PRIMARY KEY (stat_date, push_type, campaign_token)", "PRIMARY KEY (period_start, push_type, campaign_token)",
                "campaign_token               varchar(32)  NOT NULL DEFAULT ''",
                "CHECK (extract(isodow FROM period_start) = 1)",
                "CHECK (period_start = date_trunc('month', period_start)::date)",
                "CHECK (placement_type IN ('ops_popup', 'toko_banner', 'newbie_coupon'))");
        // 口径注释：周期内去重、接收近似、只有券过期有发送数
        assertThat(s).containsPattern("COMMENT ON COLUMN agg_weekly_placement\\.click_persons IS '周内去重")
                .containsPattern("COMMENT ON COLUMN agg_monthly_push\\.opened_persons IS '月内去重")
                .containsPattern("COMMENT ON COLUMN agg_daily_push\\.recipient_count IS '[^']*近似")
                .containsPattern("COMMENT ON COLUMN agg_daily_push\\.sent_count IS '只用于券过期推送");
        assertThat(s).contains("rolname = '${readerRole}'").doesNotContain("TO analytics_reader");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
        assertThat(s).doesNotContain("user_id", "anonymous_id", "person_key", "order_token");
    }

    /** Story 4.5 AC6：Toko 日 / 周 / 月表、主键、周一 / 月初约束、计数列 integer（不用 smallint）、WIB 注释与授权；不含可识别个人的列。 */
    @Test
    void tokoTables() throws IOException {
        String s = read(PATH_45);
        String[][] tables = {{"agg_daily_toko", "stat_date"}, {"agg_weekly_toko", "week_start"},
            {"agg_monthly_toko", "month_start"}};
        for (String[] t : tables) {
            assertThat(s).contains("CREATE TABLE " + t[0] + " (", "COMMENT ON TABLE " + t[0] + " IS",
                    t[1] + "  ", "format('GRANT SELECT ON %I TO %I', '" + t[0] + "', '${readerRole}')");
            assertThat(s).containsPattern("COMMENT ON COLUMN " + t[0] + "\\." + t[1] + " IS 'WIB 日期");
        }
        assertThat(s).contains("stat_date                  date         PRIMARY KEY",
                "week_start                 date         PRIMARY KEY",
                "month_start                date         PRIMARY KEY",
                "CHECK (extract(isodow FROM week_start) = 1)",
                "CHECK (month_start = date_trunc('month', month_start)::date)");
        for (String c : new String[] {"toko_viewers", "funnel_s1_toko_page", "funnel_s2_product_shown",
            "funnel_s3_detail_viewed", "funnel_s4_buy_or_cart", "funnel_s5_order_submitted", "funnel_s6_pay_tapped",
            "funnel_s7_paid", "orders_submitted", "orders_paid", "orders_created_biz", "atc_users",
            "atc_converted_users"}) {
            assertThat(s).containsPattern("(?m)^    " + c + " +integer +NOT NULL,$");
        }
        assertThat(s).doesNotContainIgnoringCase("smallint");
        assertThat(s).contains("rolname = '${readerRole}'").doesNotContain("TO analytics_reader");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
        assertThat(s).doesNotContain("user_id", "anonymous_id", "person_key");
    }

    /** Story 4.6 AC1 / AC4 / AC5：购物车与商品日表、主键、scope 约束、计数列 integer、WIB 注释与授权；不含可识别个人的列。 */
    @Test
    void cartAndProductTables() throws IOException {
        String s = read(PATH_46);
        for (String t : new String[] {"agg_daily_cart", "agg_daily_product"}) {
            assertThat(s).contains("CREATE TABLE " + t + " (", "COMMENT ON TABLE " + t + " IS",
                    "format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
            assertThat(s).containsPattern("COMMENT ON COLUMN " + t + "\\.stat_date IS 'WIB 日期");
        }
        assertThat(s).contains("PRIMARY KEY (stat_date, scope, product_token)",
                "PRIMARY KEY (stat_date, product_token)",
                "product_token         varchar(32)   NOT NULL DEFAULT ''",
                "CHECK (scope IN ('PRODUCT', 'TOTAL')", "AND ((scope = 'TOTAL') = (product_token = '')))");
        for (String c : new String[] {"add_events", "add_items", "removed_items", "removed_manual", "removed_invalid",
            "purchased_items", "ordered_unpaid_items", "in_cart_items", "dwell_lt_1h", "dwell_1h_1d", "dwell_1d_3d",
            "dwell_3d_7d", "detail_views", "add_to_cart"}) {
            assertThat(s).containsPattern("(?m)^    " + c + " +integer +NOT NULL,$");
        }
        assertThat(s).containsPattern("(?m)^    cart_value_idr +bigint +NOT NULL,$")
                .containsPattern("(?m)^    paid_qty_30d +bigint +NOT NULL,$")
                .containsPattern("(?m)^    sellable_stock +bigint +NOT NULL,$")
                .containsPattern("(?m)^    items_per_cart_avg +numeric\\(8,2\\),$");
        // 口径注释：TOTAL / PRODUCT 混加会翻倍；库存快照只首次写
        assertThat(s).contains("scope = ''PRODUCT''").containsPattern(
                "COMMENT ON COLUMN agg_daily_product\\.sellable_stock IS '快照[^']*只在行首次写入时记录");
        assertThat(s).doesNotContainIgnoringCase("smallint");
        assertThat(s).contains("rolname = '${readerRole}'").doesNotContain("TO analytics_reader");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
        assertThat(s).doesNotContain("user_id", "anonymous_id", "person_key");
    }
}
