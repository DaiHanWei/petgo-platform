package com.tailtopia.shared.error;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 仅测试用：在两条 H5 路径上制造一个<b>真正的 5xx</b>，用来验证加载失败页确实会被渲染。
 *
 * <p>为什么需要它：AC3 说的是「<b>服务端异常</b>时渲染该页面而非白屏」。
 * 用「路径不存在」去触发是<b>另一回事</b>（那是 404、是终局，按语义应走失效页），
 * 拿它当 5xx 的替身会让这条 AC 看起来通过、实际没验到。
 *
 * <p>⚠️ 两个坑，都踩过：
 * <ul>
 *   <li>路径必须是<b>两段</b>（{@code /p/boom/please}）—— 一段会与真实路由 {@code /p/{cardToken}}
 *       撞成 Ambiguous mapping，整个上下文起不来。</li>
 *   <li>本类<b>只用 {@code @Import} 引入</b>，不要再包一层 {@code @TestConfiguration} + {@code @Bean}
 *       —— 那样它会被注册两次（工厂方法一次、组件扫描一次），同样报 Ambiguous mapping。</li>
 * </ul>
 * 只在 {@code src/test} 下，生产不存在。
 */
@Controller
public class H5BoomTestController {

    /** 名片链路：{@code /p/boom/please} → 抛异常 → 应落到 card_error。 */
    @GetMapping("/p/boom/please")
    public String cardBoom() {
        throw new IllegalStateException("intentional boom for L1 test");
    }

    /** 里程碑链路：{@code /m/boom/please}。 */
    @GetMapping("/m/boom/please")
    public String milestoneBoom() {
        throw new IllegalStateException("intentional boom for L1 test");
    }
}
