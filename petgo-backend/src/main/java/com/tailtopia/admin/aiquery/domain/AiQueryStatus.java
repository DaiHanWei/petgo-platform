package com.tailtopia.admin.aiquery.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务状态机（spec §4.1，ops 侧权威状态）：
 * <pre>
 * PENDING ──提交成功──▶ RUNNING ──▶ DONE
 *    │                    ├──▶ FAILED（agent 报错 / agent 不认识该任务）
 *    └──超时──▶ TIMEOUT ◀─┘
 * </pre>
 * 状态变更一律用带旧状态条件的 UPDATE（见 {@code AiQueryMessageRepository}）。
 */
public enum AiQueryStatus {
    PENDING, RUNNING, DONE, FAILED, TIMEOUT;

    public static final Set<AiQueryStatus> UNFINISHED = EnumSet.of(PENDING, RUNNING);

    public boolean isUnfinished() {
        return UNFINISHED.contains(this);
    }
}
