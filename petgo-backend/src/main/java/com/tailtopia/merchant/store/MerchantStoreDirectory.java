package com.tailtopia.merchant.store;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code merchant_stores} 只读目录（契约第 1 节，AD-21）。
 *
 * <p>🔴 <b>只读四个契约列</b>：{@code id}、{@code invite_code}、{@code status}、{@code public_token}。
 * 其余列（店名、手机号、密码哈希……）归商家平台，本仓库不读 —— 读了就是把那边的改动变成这边的故障。
 * 不加锁、不写。
 */
@Component
public class MerchantStoreDirectory {

    /** 契约 {@code status} 取值只有 {@code ACTIVE} / {@code DISABLED}。 */
    public static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;

    public MerchantStoreDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按邀请码查门店（调用方已去空格）。 */
    public Optional<MerchantStoreRef> findByInviteCode(String inviteCode) {
        return first(jdbc.query(
                "SELECT id, invite_code, status, public_token FROM merchant_stores WHERE invite_code = ?",
                (rs, i) -> new MerchantStoreRef(rs.getLong("id"), rs.getString("invite_code"),
                        rs.getString("status"), rs.getString("public_token")),
                inviteCode));
    }

    public Optional<MerchantStoreRef> findById(long id) {
        return first(jdbc.query(
                "SELECT id, invite_code, status, public_token FROM merchant_stores WHERE id = ?",
                (rs, i) -> new MerchantStoreRef(rs.getLong("id"), rs.getString("invite_code"),
                        rs.getString("status"), rs.getString("public_token")),
                id));
    }

    private static Optional<MerchantStoreRef> first(List<MerchantStoreRef> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 门店的契约四列。 */
    public record MerchantStoreRef(long id, String inviteCode, String status, String publicToken) {

        public boolean isActive() {
            return ACTIVE.equals(status);
        }
    }
}
