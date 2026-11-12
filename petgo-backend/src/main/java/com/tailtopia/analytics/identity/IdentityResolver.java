package com.tailtopia.analytics.identity;

import com.tailtopia.analytics.ingest.RawEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * App 上报事件的身份判定点（架构 A7）。输入：本批已校验的事件（{@code userKey} 里是请求声明的 {@code userId} 原值）
 * + 可选 JWT；输出：每条事件填好最终的 {@code userKey} / {@code userId} / {@code identityUnverified}。
 *
 * <p>analytics-pipeline Story 1.4 只提供 {@link Passthrough 直通实现}；Story 1.5 换成查关联表的正式实现
 * （🔴 届时<b>只认 {@code role=USER} 的 token</b>：兽医 token 的 {@code sub} 是 vetId，与 {@code users.id} 撞号，
 * 其他角色按无 JWT 处理）。服务端事件（Story 1.9）不经过这里。
 *
 * <p>实现不得在日志里打用户标识、匿名 id 或 JWT 内容。
 */
public interface IdentityResolver {

    /**
     * 判定一批事件的身份。
     *
     * @param events 已校验的事件，顺序保持
     * @param jwt    请求带的有效 JWT；匿名请求为 {@code null}（无效 / 过期 token 已被安全过滤器 401，到不了这里）
     * @return 与入参等长、同序的事件
     */
    List<RawEvent> resolve(List<RawEvent> events, Jwt jwt);

    /**
     * Story 1.4 直通实现（AC10）：有用户标识 → {@code userKey} 原样、{@code userId = null}、{@code identityUnverified = true}；
     * 没有 → 三项都空 / false。JWT 不参与判定。Story 1.5 替换。
     */
    @Component
    final class Passthrough implements IdentityResolver {

        @Override
        public List<RawEvent> resolve(List<RawEvent> events, Jwt jwt) {
            List<RawEvent> out = new ArrayList<>(events.size());
            for (RawEvent e : events) {
                String claimed = e.userKey();
                out.add(claimed == null ? e.withIdentity(null, null, false) : e.withIdentity(claimed, null, true));
            }
            return out;
        }
    }
}
