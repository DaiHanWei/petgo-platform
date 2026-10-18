package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.4.0 ④ Story 1.2 · AC5（NFR1）—— 所有可关类型的推送出口都经 {@code PushPreferenceService}。
 *
 * <p>纯源码扫描（无 Spring、无 DB）。以后新增推送出口不经闸门 → 本测试红：去改这里的白名单，
 * <b>并</b>在新出口里先调 {@code PushPreferenceService.isEnabled / mutedAmong}（「订单与账号」类除外，
 * 见 CROSS-STORY-DECISIONS「推送分类开关（④）」）。
 */
class PushExitStructureTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final String BASE = "com/tailtopia/";

    /** 调用或方法引用（{@code pusher::pushToUser}）都算。 */
    private static final Pattern PUSHER_CALL = Pattern.compile("(\\.|::)pushToUser(Now)?\\b");
    private static final Pattern IM_OFFLINE_CALL = Pattern.compile("(\\.|::)pushOffline(Checked)?\\b");
    private static final Pattern IM_SYSTEM_MESSAGE_CALL = Pattern.compile("(\\.|::)sendSystemMessage\\b");
    private static final Pattern GATE_CALL = Pattern.compile("!pushAllowed\\(");

    private static final Set<String> PUSHER_CALLERS = Set.of(
            BASE + "notify/service/NotificationService.java",
            BASE + "notify/ops/service/OpsNotificationDispatcher.java");

    @Test
    @DisplayName("NotificationPusher.pushToUser / pushToUserNow 只在两处已加闸门的类里被调用")
    void pusherCalledOnlyFromGatedClasses() {
        assertThat(callers(PUSHER_CALL)).as("新的推送出口必须先经 PushPreferenceService").isSubsetOf(PUSHER_CALLERS);
        for (String f : PUSHER_CALLERS) {
            assertThat(read(MAIN.resolve(f))).as(f + " 引用 PushPreferenceService").contains("PushPreferenceService");
        }
    }

    @Test
    @DisplayName("白名单文件内：NotificationService 每个 pushToUser 调用点各配一道 pushAllowed；dispatcher 只一处推送且预取关闭名单")
    void everyCallSiteInsideWhitelistIsGated() {
        String svc = stripComments(read(MAIN.resolve(BASE + "notify/service/NotificationService.java")));
        assertThat(count(GATE_CALL, svc)).as("在 NotificationService 加了新的 pushToUser 调用点？先补 pushAllowed 闸门")
                .isEqualTo(count(PUSHER_CALL, svc));
        String ops = stripComments(read(MAIN.resolve(BASE + "notify/ops/service/OpsNotificationDispatcher.java")));
        assertThat(count(PUSHER_CALL, ops)).isEqualTo(1);
        assertThat(ops).contains("prefs.mutedAmong(");
    }

    @Test
    @DisplayName("TencentImClient.pushOffline* 只在 NotificationPusher 里被调用")
    void imOfflinePushOnlyFromPusher() {
        assertThat(callers(IM_OFFLINE_CALL)).containsOnly(BASE + "notify/service/NotificationPusher.java");
    }

    @Test
    @DisplayName("IM 系统消息（自带离线推送）只在问诊模块（P-3 豁免：属订单与账号）")
    void imSystemMessageOnlyFromConsult() {
        assertThat(callers(IM_SYSTEM_MESSAGE_CALL)).isNotEmpty()
                .allMatch(f -> f.startsWith(BASE + "consult/"), "位于 consult/ 下");
    }

    /** 去掉注释行后含调用形状的 main 源文件（相对 src/main/java，正斜杠）。 */
    private static Set<String> callers(Pattern call) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                if (call.matcher(stripComments(read(p))).find()) {
                    out.add(MAIN.relativize(p).toString().replace('\\', '/'));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(out);
    }

    private static long count(Pattern p, String src) {
        return p.matcher(src).results().count();
    }

    /**
     * 粗略去注释：块注释与整行 {@code //} 注释（只为不让 javadoc 里的 {@code #pushOffline} 之类误报）。
     * 行尾 {@code //} 不剥 —— 否则字符串里的 {@code "https://…"} 会把同一行后面的调用一起剥掉（漏报比误报更糟）。
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\n")) {
            if (!line.strip().startsWith("//")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
