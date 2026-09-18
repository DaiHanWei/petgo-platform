package com.tailtopia.config.repository;

import com.tailtopia.config.domain.ConfigChangeLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 配置变更日志仓储（Story 9.2，append-only）。 */
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long>, JpaSpecificationExecutor<ConfigChangeLog> {

    /**
     * 某一类配置的变更分页 + 筛选（V1.3.0 Story 6.4 变更记录抽屉）：{@code field} 可空 = 全部参数；{@code from} / {@code to} 为 UTC 半开区间
     * [from, to)，调用方按 WIB 自然日折算；排序由调用方 Pageable 携带（约定 {@code changedAt desc, id desc}）。
     */
    default Page<ConfigChangeLog> search(ConfigChangeLog.ConfigType type, String field, Instant from, Instant to, Pageable pageable) {
        Specification<ConfigChangeLog> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("configType"), type));
            if (field != null) {
                ps.add(cb.equal(root.get("field"), field));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("changedAt"), from));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("changedAt"), to));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0])); // 排序由 Pageable 携带（changedAt desc, id desc）
        };
        return findAll(spec, pageable);
    }

    List<ConfigChangeLog> findTop100ByOrderByChangedAtDesc();

    /**
     * 某一类配置最近的变更（「算法参数」页用，2026-08-26）。
     *
     * <p>🔴 页面上直接展示它不是锦上添花：本平台**没有 A/B 实验基建**，
     * 改了参数之后无法判定对错，只能看整体指标漂移 ——
     * 而「谁在什么时候把哪个值从多少改成了多少」是唯一能与那次漂移对上的锚点。
     * 埋在审计后台里没人会去翻，必须摆在改参数的同一屏上。
     */
    List<ConfigChangeLog> findTop20ByConfigTypeOrderByChangedAtDesc(ConfigChangeLog.ConfigType configType);
}
