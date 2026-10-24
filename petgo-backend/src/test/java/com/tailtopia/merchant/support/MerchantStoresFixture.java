package com.tailtopia.merchant.support;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试用 {@code merchant_stores} 行（那张表归商家平台）。
 *
 * <p>CI 在跑完本仓库迁移后会执行商家仓库的迁移（契约第 6 节），表已存在；本机没跑商家迁移时，这里按商家平台
 * {@code V20261021_1200__merchant_foundation} 的 NOT NULL 列建一张同形的表（{@code IF NOT EXISTS}，已有则不动）。
 * 插入只写商家平台表的 NOT NULL 列，两种情况下都能插进去。
 */
public final class MerchantStoresFixture {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private MerchantStoresFixture() {
    }

    public static void ensureTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS merchant_stores (
                    id                  BIGSERIAL PRIMARY KEY,
                    public_token        VARCHAR(32)  NOT NULL UNIQUE,
                    name                VARCHAR(80)  NOT NULL,
                    contact_name        VARCHAR(60)  NOT NULL,
                    login_phone         VARCHAR(20)  NOT NULL UNIQUE,
                    password_hash       VARCHAR(100) NOT NULL,
                    invite_code         VARCHAR(8)   NOT NULL UNIQUE,
                    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
                    created_by_admin_id BIGINT       NOT NULL
                )""");
    }

    /** 新建一家门店，返回 {@code [id, inviteCode]}。邀请码 6 位数字、跨运行不撞。 */
    public static Store insert(JdbcTemplate jdbc, String status) {
        ensureTable(jdbc);
        long n = SEQ.incrementAndGet();
        String code = String.format("%06d", Math.floorMod(n * 7919, 1_000_000));
        // 撞码（极小概率，共享库里别的运行留下的）就换一个
        while (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM merchant_stores WHERE invite_code = ?)", Boolean.class, code))) {
            n = SEQ.incrementAndGet();
            code = String.format("%06d", Math.floorMod(n * 7919, 1_000_000));
        }
        String phone = "+628" + String.format("%010d", Math.floorMod(System.nanoTime() + n, 10_000_000_000L));
        Long id = jdbc.queryForObject("""
                INSERT INTO merchant_stores (public_token, name, contact_name, login_phone, password_hash,
                        invite_code, status, created_by_admin_id)
                VALUES (?, 'Toko Test', 'Budi', ?, 'x', ?, ?, 1) RETURNING id""",
                Long.class, "mst" + n + "x" + System.nanoTime() % 100000, phone, code, status);
        return new Store(id, code);
    }

    public static void setStatus(JdbcTemplate jdbc, long id, String status) {
        jdbc.update("UPDATE merchant_stores SET status = ? WHERE id = ?", status, id);
    }

    public record Store(long id, String inviteCode) {
    }
}
