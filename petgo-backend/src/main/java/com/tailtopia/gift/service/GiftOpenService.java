package com.tailtopia.gift.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拆礼物（V1.5.0 gift-pack Story 1-4 · AC4 · D-7）：<b>只写 {@code opened_at}，不入账</b>——EXP 已在送达时由
 * {@code GiftSentEvent} 入过。🔴 本类不得注入成长值 / 钱包 / 通知（{@code GiftOpenServiceDependencyTest} 守卫）。
 *
 * <p>条件 UPDATE（{@code opened_at IS NULL}）保证幂等与并发安全：两次拆同一笔，第二次影响 0 行、调用方据此标
 * {@code alreadyOpened}。主人校验在调用方（{@code GiftShowcaseAccess}）。
 */
@Service
public class GiftOpenService {

    /** 「全部拆开」一次最多 200 笔（按送达时刻升序取最早的）。 */
    public static final int OPEN_ALL_LIMIT = 200;
    /** 按 token 拆一次最多 50 笔。 */
    public static final int OPEN_TOKENS_MAX = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final GiftOrderQuery query;

    public GiftOpenService(NamedParameterJdbcTemplate jdbc, GiftOrderQuery query) {
        this.jdbc = jdbc;
        this.query = query;
    }

    /**
     * 结果。
     *
     * @param involvedIds 本次请求涉及的订单（含本来就已拆的）
     * @param openedNow   本次真正拆开的
     */
    public record Result(List<Long> involvedIds, Set<Long> openedNow) {
    }

    /** 按 token 拆（不属于这只宠物 / 未送达的 token 忽略）。 */
    @Transactional
    public Result openTokens(long petId, Collection<String> tokens) {
        Set<String> distinct = new LinkedHashSet<>(tokens);
        List<Long> opened = jdbc.queryForList("""
                UPDATE gift_orders SET opened_at = now()
                 WHERE pet_id = :petId AND status = 'DELIVERED' AND opened_at IS NULL AND public_token IN (:t)
                RETURNING id
                """, Map.of("petId", petId, "t", distinct), Long.class);
        List<Long> involved = query.deliveredIdsOfPet(petId, distinct);
        return new Result(involved, Set.copyOf(opened));
    }

    /** 全部拆开：最早的 200 笔未拆（外层再判 {@code opened_at IS NULL}，并发重检时已被拆的自动剔除）。 */
    @Transactional
    public Result openAll(long petId) {
        List<Long> opened = jdbc.queryForList("""
                UPDATE gift_orders SET opened_at = now()
                 WHERE opened_at IS NULL AND status = 'DELIVERED' AND pet_id = :petId
                   AND id IN (SELECT id FROM gift_orders
                               WHERE pet_id = :petId AND status = 'DELIVERED' AND opened_at IS NULL
                               ORDER BY delivered_at ASC, id ASC
                               LIMIT :lim)
                RETURNING id
                """, Map.of("petId", petId, "lim", OPEN_ALL_LIMIT), Long.class);
        return new Result(opened, Set.copyOf(opened));
    }
}
