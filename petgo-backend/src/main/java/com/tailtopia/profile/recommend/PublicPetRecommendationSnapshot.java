package com.tailtopia.profile.recommend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 游客推荐宠物的 10 分钟快照（V1.5.0 onboarding-recall Story 1.1 · OR-5 · AC7）。
 *
 * <h2>🔴 这是 PRD 拍板的唯一例外，不是通用缓存层</h2>
 * PRD「游客结果服务端缓存 10 分钟」。架构护栏禁止引入缓存中间件（Caffeine / Redis / {@code @Cacheable} 一律不加），
 * 所以这里只是<b>进程内一个单值</b>：固定的全站段第一页（{@code limit = 10}、无查看者），
 * 过期后由下一个请求重建。单实例部署（V1 姿态）下语义正确；登录态推荐（按查看者算）仍一律实时，
 * 见 {@link PetRecommendationService} 类注释。
 *
 * <h2>并发</h2>
 * <ul>
 *   <li>读：{@link AtomicReference}，无锁；</li>
 *   <li>过期重建：非阻塞抢重建权（CAS），同一时刻只有一个线程在建（防并发重建风暴）；
 *       其他请求<b>读旧值</b>，不排队等；</li>
 *   <li>冷启动（还没有任何值）：{@code synchronized} 排队等第一个建好。</li>
 *   <li>重建抛异常：有旧值 → 返回旧值（下一请求再试）；没有 → 原样抛（控制器转 RFC 9457 500，
 *       App 按失败处理、整块不渲染）。</li>
 * </ul>
 */
@Component
public class PublicPetRecommendationSnapshot {

    /** 快照固定取的张数（游客端点 limit 上限）。 */
    static final int SNAPSHOT_SIZE = PetRecommendationService.PUBLIC_MAX_LIMIT;

    private final PetRecommendationService recommendations;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Object rebuildLock = new Object();
    /** 重建中标志（非阻塞抢锁：抢不到的线程去读旧值，不排队）。 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    @Autowired
    public PublicPetRecommendationSnapshot(PetRecommendationService recommendations,
            @Value("${petgo.public-pet-recommendations.ttl:PT10M}") Duration ttl) {
        this(recommendations, ttl, Clock.systemUTC());
    }

    PublicPetRecommendationSnapshot(PetRecommendationService recommendations, Duration ttl, Clock clock) {
        this.recommendations = recommendations;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 全站第一页的前 {@code limit} 张（{@code limit} 按 1 ~ 10 收口）。 */
    public List<RecommendedPetResponse> firstPage(int limit) {
        int capped = Math.max(1, Math.min(limit, SNAPSHOT_SIZE));
        List<RecommendedPetResponse> items = currentItems();
        return items.size() <= capped ? items : List.copyOf(items.subList(0, capped));
    }

    private List<RecommendedPetResponse> currentItems() {
        Instant now = clock.instant();
        Snapshot snap = current.get();
        if (snap != null && !snap.expiredAt(now, ttl)) {
            return snap.items();
        }
        if (snap != null) {
            // 已过期但有旧值：抢到重建权的线程去建，其他线程直接读旧值。
            if (!tryRebuild(now)) {
                return snap.items();
            }
            return current.get().items();
        }
        // 冷启动：没有任何值可读，排队等第一个建好。
        synchronized (rebuildLock) {
            Snapshot again = current.get();
            if (again != null) {
                return again.items();
            }
            Snapshot built = build(now);
            current.set(built);
            return built.items();
        }
    }

    /**
     * 抢重建权并重建；抢不到（有人在建）返回 false。重建失败吞掉异常、保留旧值，返回 false。
     */
    private boolean tryRebuild(Instant now) {
        if (!rebuilding.compareAndSet(false, true)) {
            return false;
        }
        try {
            Snapshot snap = current.get();
            if (snap != null && !snap.expiredAt(now, ttl)) {
                return true; // 别的线程刚建好
            }
            try {
                current.set(build(now));
                return true;
            } catch (RuntimeException e) {
                return false; // 有旧值 → 返回旧值，下一请求再试
            }
        } finally {
            rebuilding.set(false);
        }
    }

    private Snapshot build(Instant now) {
        return new Snapshot(List.copyOf(recommendations.publicFirstPage(SNAPSHOT_SIZE, now)), now);
    }

    private record Snapshot(List<RecommendedPetResponse> items, Instant builtAt) {
        boolean expiredAt(Instant now, Duration ttl) {
            return !now.isBefore(builtAt.plus(ttl));
        }
    }
}
