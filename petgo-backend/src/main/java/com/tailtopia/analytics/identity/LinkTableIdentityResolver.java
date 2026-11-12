package com.tailtopia.analytics.identity;

import com.tailtopia.analytics.ingest.RawEvent;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * A7 身份校验的正式实现（analytics-pipeline Story 1.5）：逐条判定事件声明的用户标识可不可信，并收集本批要追加的登录关联。
 *
 * <h2>token 用户</h2>
 * JWT 非空、{@code role} 声明为 {@code USER}、{@code sub} 可解析为 long 才算有 token 用户
 * （判法同 {@code OpsPopupController#viewerId}）；兽医 / 后台等其他角色的 token <b>按无 JWT 处理</b>
 * （兽医 {@code sub} 是 vetId，与 {@code users.id} 撞号）。
 *
 * <h2>新关联</h2>
 * 有 token 用户时，本批里 {@code event == "user_identified"} 且声明的 {@code userId} 等于 {@code tokenUserKey} 的每一条
 * 生成一行 {@link IdentityLink}，挂在该事件上随队列由 flusher 同事务写库，并进「本批已知关联」供本批判定。
 * 声明与 token 不符的 {@code user_identified} <b>不生成关联</b>（防止拿别人的 token 给自己的设备挂关联）。
 *
 * <h2>逐条判定（{@code userKey} = 请求里声明的 {@code userId}）</h2>
 * <ol>
 *   <li>没有 {@code userKey} → 匿名：{@code userKey = null}、{@code userId = null}、未标记。</li>
 *   <li>有 token 用户且 {@code userKey == tokenUserKey} → 通过，{@code userId = tokenUserId}。</li>
 *   <li>其余 → 先查本批已知关联，再查 {@link IdentityLinkRepository#findUserId}：查到 → 通过，{@code userId} 取关联里的用户；
 *       查不到 → 去掉用户标识（{@code userKey = null}、{@code userId = null}）并标 {@code identityUnverified}。</li>
 * </ol>
 * 同一批里同一个（匿名 id, {@code userKey}）只查一次库（批内 {@link Map}，<b>不做跨请求缓存</b>——护栏禁止通用缓存层）。
 * 埋点库查询异常 → 本批此后需要查库的事件一律按查不到处理（剥离 + 标记），不抛、不让接口 500。
 *
 * <h2>计数 / 日志（AC4）</h2>
 * 校验不通过的条数累计在 {@link AtomicLong}，每 5 分钟（计数 &gt; 0 时）打一条 INFO 汇总并清零。
 * 🔴 日志一律不出现匿名 id、用户标识、用户 id，只打条数（NFR-3、架构 §实现规范 4）。
 */
@Component
public class LinkTableIdentityResolver implements IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(LinkTableIdentityResolver.class);

    /** App 登录成功后上报的事件名（Story 2.2 发；Story 1.2 已在清单预登记）。 */
    public static final String USER_IDENTIFIED = "user_identified";

    static final String USER_ROLE = "USER";

    private final IdentityLinkRepository links;
    private final AtomicLong unverifiedCount = new AtomicLong();

    public LinkTableIdentityResolver(IdentityLinkRepository links) {
        this.links = links;
    }

    @Override
    public List<RawEvent> resolve(List<RawEvent> events, Jwt jwt) {
        Long tokenUserId = tokenUserId(jwt);
        String tokenUserKey = tokenUserId == null ? null : AnalyticsDistinctId.of(tokenUserId);

        // 1. 收集本批新关联
        IdentityLink[] newLinks = new IdentityLink[events.size()];
        Map<LinkKey, Long> batchLinks = new HashMap<>();
        if (tokenUserKey != null) {
            for (int i = 0; i < events.size(); i++) {
                RawEvent e = events.get(i);
                if (USER_IDENTIFIED.equals(e.event()) && tokenUserKey.equals(e.userKey()) && e.anonymousId() != null) {
                    newLinks[i] = new IdentityLink(e.anonymousId(), tokenUserId, tokenUserKey,
                            IdentityLink.correctedTime(e.clientTs(), e.sentAt(), e.receivedAt()), e.messageId());
                    batchLinks.putIfAbsent(new LinkKey(e.anonymousId(), tokenUserKey), tokenUserId);
                }
            }
        }

        // 2. 逐条判定
        Lookup lookup = new Lookup();
        List<RawEvent> out = new ArrayList<>(events.size());
        int unverified = 0;
        for (int i = 0; i < events.size(); i++) {
            RawEvent e = events.get(i);
            String claimed = e.userKey();
            RawEvent resolved;
            if (claimed == null) {
                resolved = e.withIdentity(null, null, false);
            } else if (tokenUserKey != null && tokenUserKey.equals(claimed)) {
                resolved = e.withIdentity(claimed, tokenUserId, false);
            } else {
                Long linkedUser = null;
                if (e.anonymousId() != null) {
                    LinkKey key = new LinkKey(e.anonymousId(), claimed);
                    linkedUser = batchLinks.get(key);
                    if (linkedUser == null) {
                        linkedUser = lookup.find(key);
                    }
                }
                if (linkedUser != null) {
                    resolved = e.withIdentity(claimed, linkedUser, false);
                } else {
                    resolved = e.withIdentity(null, null, true);
                    unverified++;
                }
            }
            out.add(newLinks[i] == null ? resolved : resolved.withIdentityLink(newLinks[i]));
        }
        if (unverified > 0) {
            unverifiedCount.addAndGet(unverified);
        }
        if (lookup.failed) {
            log.warn("analytics identity link lookup failed, treating as unverified: cause={}", lookup.cause);
        }
        return out;
    }

    /**
     * 5 分钟汇总日志：计数 &gt; 0 时打一条 INFO 并清零。日志里只有条数。
     *
     * @return 本次汇总的条数（0 = 未打日志）
     */
    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    long logUnverifiedSummary() {
        long n = unverifiedCount.getAndSet(0);
        if (n > 0) {
            log.info("analytics identity unverified count={} window=5m", n);
        }
        return n;
    }

    /** 当前累计（尚未汇总）的校验不通过条数。 */
    long unverifiedCount() {
        return unverifiedCount.get();
    }

    /** 只认 {@code role=USER}、{@code sub} 可解析为 long 的 token；其余一律 {@code null}（按无 JWT 处理）。 */
    static Long tokenUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !USER_ROLE.equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record LinkKey(String anonymousId, String userKey) {
    }

    /** 批内记忆的关联表查询；首次异常后本批不再查库，后续一律按查不到。 */
    private final class Lookup {
        private final Map<LinkKey, Optional<Long>> memo = new HashMap<>();
        private boolean failed;
        private String cause;

        Long find(LinkKey key) {
            Optional<Long> hit = memo.get(key);
            if (hit == null) {
                if (failed) {
                    return null;
                }
                try {
                    hit = links.findUserId(key.anonymousId(), key.userKey());
                } catch (RuntimeException ex) {
                    failed = true;
                    cause = ex.getClass().getSimpleName();
                    return null;
                }
                memo.put(key, hit == null ? Optional.empty() : hit);
            }
            return hit.orElse(null);
        }
    }
}
