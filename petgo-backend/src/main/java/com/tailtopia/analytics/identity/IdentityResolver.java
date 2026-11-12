package com.tailtopia.analytics.identity;

import com.tailtopia.analytics.ingest.RawEvent;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * App 上报事件的身份判定点（架构 A7）。输入：本批已校验的事件（{@code userKey} 里是请求声明的 {@code userId} 原值）
 * + 可选 JWT；输出：每条事件填好最终的 {@code userKey} / {@code userId} / {@code identityUnverified}，
 * 生成了关联的 {@code user_identified} 事件另挂上 {@link IdentityLink}（{@link RawEvent#identityLink()}）。
 *
 * <p>正式实现见 {@link LinkTableIdentityResolver}（analytics-pipeline Story 1.5，替换 Story 1.4 的直通实现）。
 * 🔴 只认 {@code role=USER} 的 token：兽医 token 的 {@code sub} 是 vetId，与 {@code users.id} 撞号，其他角色按无 JWT 处理。
 * 服务端事件（Story 1.9）不经过这里。
 *
 * <p>实现不得在日志里打用户标识、匿名 id、用户 id 或 JWT 内容。
 */
@FunctionalInterface
public interface IdentityResolver {

    /**
     * 判定一批事件的身份。
     *
     * @param events 已校验的事件，顺序保持
     * @param jwt    请求带的有效 JWT；匿名请求为 {@code null}（无效 / 过期 token 已被安全过滤器 401，到不了这里）
     * @return 与入参等长、同序的事件
     */
    List<RawEvent> resolve(List<RawEvent> events, Jwt jwt);
}
