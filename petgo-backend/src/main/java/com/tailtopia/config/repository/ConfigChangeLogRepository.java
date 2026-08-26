package com.tailtopia.config.repository;

import com.tailtopia.config.domain.ConfigChangeLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 配置变更日志仓储（Story 9.2，append-only）。 */
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long> {

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
