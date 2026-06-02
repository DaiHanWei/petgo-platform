package com.petgo.consult.service;

import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 待接单队列态（Story 5.3，Redis 收窄用途之一「问诊队列态」）。
 *
 * <p>结构：ZSET {@code consult:waiting} member=sessionId score=入队时刻(epochMillis)，FIFO。
 * DB {@code consult_sessions} 为权威，Redis 为兽医待接单的快速读源（Story 5.5 接单读队列）。
 * <b>只存 sessionId + 排序键，不存会话内容</b>（架构 Redis 收窄）。
 */
@Service
public class ConsultQueueService {

    static final String WAITING_ZSET = "consult:waiting";

    private final StringRedisTemplate redis;

    public ConsultQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 入队（发起 WAITING 时）。 */
    public void enqueue(long sessionId) {
        redis.opsForZSet().add(WAITING_ZSET, String.valueOf(sessionId), System.currentTimeMillis());
    }

    /** 出队（取消 / 接单 / 不再等待时）。 */
    public void dequeue(long sessionId) {
        redis.opsForZSet().remove(WAITING_ZSET, String.valueOf(sessionId));
    }

    /** 当前待接单 sessionId（FIFO，Story 5.5 接单消费）。 */
    public List<Long> waitingSessionIds() {
        Set<String> members = redis.opsForZSet().range(WAITING_ZSET, 0, -1);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).toList();
    }
}
