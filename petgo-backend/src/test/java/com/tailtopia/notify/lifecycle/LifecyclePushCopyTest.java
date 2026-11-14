package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * L0：生命周期推送文案守护（留存运营作战手册 · 抓手 1）。
 *
 * <p>钉住手册的<b>铁律</b>：「永远用『记录你的宠物』，不要用『回来看看』——
 * 后者是空话，前者是用户自己用行为承认过的动机。」这不是风格偏好，是这一版运营的核心假设，
 * 所以用测试锁住，而不是靠 review 时有人记得。
 *
 * <p>同时守住三件容易在改文案时静默破掉的事：
 * <ol>
 *   <li>三语键集不能走散 —— 少一份，那个语言的用户会收到英文调试兜底串。</li>
 *   <li>面向「已建档」用户的文案必须真的带得出宠物名（{@code {0}} 占位符）。</li>
 *   <li>串必须能过 {@code MessageFormat} —— 一个没转义的单引号就会把后面的占位符整段吞掉，
 *       用户收到的是半句话。</li>
 * </ol>
 */
class LifecyclePushCopyTest {

    private static final List<String> LOCALES = List.of("id", "en", "zh_CN");

    /**
     * 全部「节点 × 分层」组合，与 {@link LifecyclePushPlanner} 实际会产出的一致（V1.5.0 onboarding-recall Story 6.1 · OR-23）。
     * V1.5.0 前的 {@code D3.FEED / D7.REVIEW / D7.RECORD} 计划器已不产出（键仍保留在文件里，不再守）。
     */
    private static final List<String> COPY_KEYS = List.of(
            "LIFECYCLE_D1.NO_PET", "LIFECYCLE_D1.CREATE_PROFILE", "LIFECYCLE_D1.RECORD", "LIFECYCLE_D1.SQUAD",
            "LIFECYCLE_D1.ACTIVE",
            "LIFECYCLE_D3.NO_PET", "LIFECYCLE_D3.CREATE_PROFILE", "LIFECYCLE_D3.SQUAD", "LIFECYCLE_D3.BESTIE",
            "LIFECYCLE_D7.CREATE_PROFILE", "LIFECYCLE_D7.GRADUATED", "LIFECYCLE_D7.TASKS_LEFT",
            "LIFECYCLE_WINBACK.RECORD", "LIFECYCLE_WINBACK.CREATE_PROFILE");

    /** 有宠物的分层（C / D / E）：文案必须说得出宠物的名字（手册铁律）。 */
    private static final List<String> MUST_NAME_THE_PET = List.of(
            "LIFECYCLE_D1.RECORD", "LIFECYCLE_D1.SQUAD", "LIFECYCLE_D1.ACTIVE",
            "LIFECYCLE_D3.SQUAD", "LIFECYCLE_D3.BESTIE", "LIFECYCLE_WINBACK.RECORD");

    /** A 没宠物 / B 待建档：没有宠物可叫，文案<b>不得</b>带宠物名。 */
    private static final List<String> MUST_NOT_NAME_A_PET = List.of(
            "LIFECYCLE_D1.NO_PET", "LIFECYCLE_D3.NO_PET",
            "LIFECYCLE_D1.CREATE_PROFILE", "LIFECYCLE_D3.CREATE_PROFILE", "LIFECYCLE_D7.CREATE_PROFILE");

    /**
     * OQ-5 例外：第 7 天「刚毕业」「还差 N 项」是<b>账号级内容</b>（新手任务属于账号，不属于某只宠物），
     * 豁免「必须带宠物名」规则；A 层（没宠物）也会收到，所以同样不得带宠物名。
     */
    private static final List<String> ACCOUNT_LEVEL_EXEMPT = List.of(
            "LIFECYCLE_D7.GRADUATED", "LIFECYCLE_D7.TASKS_LEFT");

    /**
     * 手册明令禁止的空话。命中即失败 —— 「回来看看」不给用户任何回来的理由，
     * 这正是 D1 卡在 9.6% 的推送长什么样。
     */
    private static final List<String> BANNED = List.of(
            "回来看看", "回来看一看", "come back and see", "come back and take a look",
            "kembali lihat", "lihat-lihat lagi");

    @Test
    void everyCopyKeyExistsInAllThreeLocales() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : COPY_KEYS) {
                assertThat(props.getProperty("notify." + key + ".title"))
                        .as(locale + " 缺 notify." + key + ".title").isNotNull().isNotBlank();
                assertThat(props.getProperty("notify." + key + ".body"))
                        .as(locale + " 缺 notify." + key + ".body").isNotNull().isNotBlank();
            }
        }
    }

    @Test
    void copyForUsersWithAProfileNamesThatPet() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : MUST_NAME_THE_PET) {
                String title = props.getProperty("notify." + key + ".title");
                String body = props.getProperty("notify." + key + ".body");
                assertThat(title + " " + body)
                        .as(locale + " 的 " + key + " 必须带宠物名占位符 {0}（手册铁律）")
                        .contains("{0}");
            }
        }
    }

    @Test
    void copyForUsersWithoutAPetNeverNamesOne() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : concat(MUST_NOT_NAME_A_PET, ACCOUNT_LEVEL_EXEMPT)) {
                String text = props.getProperty("notify." + key + ".title") + " "
                        + props.getProperty("notify." + key + ".body");
                assertThat(text).as(locale + " 的 " + key + " 是 A / B 层或账号级内容，不得带宠物名 {0}")
                        .doesNotContain("{0}");
            }
        }
    }

    @Test
    void tasksLeftCarriesTheCount() throws IOException {
        for (String locale : LOCALES) {
            assertThat(load(locale).getProperty("notify.LIFECYCLE_D7.TASKS_LEFT.body"))
                    .as(locale + " 还差 N 项必须带 {2}").contains("{2}");
        }
    }

    @Test
    void speciesWordsExistForEveryPetType() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String type : List.of("CAT", "DOG", "OTHER")) {
                assertThat(props.getProperty("notify.species." + type)).as(locale + " 缺 notify.species." + type)
                        .isNotBlank();
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    void everyCopyRendersCleanlyThroughMessageFormat() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : COPY_KEYS) {
                for (String suffix : List.of("title", "body")) {
                    String raw = props.getProperty("notify." + key + "." + suffix);
                    String rendered = new MessageFormat(raw, Locale.forLanguageTag("id"))
                            .format(new Object[] {"Mochi", "Persia", "3"});
                    // 渲染后不得残留占位符（说明串里写了 {1} 之类没人给值的参数）。
                    assertThat(rendered).as(locale + " · " + key + "." + suffix + " 渲染后仍有占位符")
                            .doesNotContain("{").doesNotContain("}");
                    // 单引号没转义时 MessageFormat 会吞掉后面的内容 —— 用「渲染前后长度骤减」兜住。
                    assertThat(rendered.length())
                            .as(locale + " · " + key + "." + suffix + " 疑似单引号未转义（内容被吞）")
                            .isGreaterThanOrEqualTo(raw.length() - countPlaceholders(raw) * "{0}".length()
                                    - countDoubledQuotes(raw));
                }
            }
        }
    }

    @Test
    void bannedEmptyPhrasesNeverAppear() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : COPY_KEYS) {
                for (String suffix : List.of("title", "body")) {
                    String raw = props.getProperty("notify." + key + "." + suffix).toLowerCase(Locale.ROOT);
                    for (String banned : BANNED) {
                        if (raw.contains(banned.toLowerCase(Locale.ROOT))) {
                            offenders.add(locale + " · " + key + "." + suffix + " → " + banned);
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("手册铁律：永远用「记录你的宠物」，不要用「回来看看」")
                .isEmpty();
    }

    @Test
    void copyFitsTheNotificationColumns() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : COPY_KEYS) {
                assertThat(props.getProperty("notify." + key + ".title").length())
                        .as(locale + " · " + key + " title 超出 notifications.title VARCHAR(120)")
                        .isLessThanOrEqualTo(120);
                assertThat(props.getProperty("notify." + key + ".body").length())
                        .as(locale + " · " + key + " body 超出 notifications.body VARCHAR(255)")
                        .isLessThanOrEqualTo(255);
            }
        }
    }

    /**
     * 回归钉子：生日推送曾经丢掉宠物名 —— dispatcher 拼好了「Mochi 明天满 3 岁」，
     * 却被静态键 {@code notify.PET_BIRTHDAY.body}（"今天是你家宠物生日"）整句覆盖，
     * 而且计划器是<b>提前 1 天</b>推的，文案却说"今天"。参数化后这条不许再回去。
     */
    @Test
    void scheduledPushCopyAlsoNamesThePet() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String key : List.of(NotificationType.PET_BIRTHDAY.name(),
                    NotificationType.COMPANION_ANNIVERSARY.name(),
                    "MILESTONE_NODE." + com.tailtopia.notify.schedule.ScheduledPushPlanner.FIRST_BIRTHDAY_NODE)) {
                String title = props.getProperty("notify." + key + ".title");
                String body = props.getProperty("notify." + key + ".body");
                assertThat(title).as(locale + " 缺 notify." + key + ".title").isNotNull();
                assertThat(body).as(locale + " 缺 notify." + key + ".body").isNotNull();
                assertThat(title + " " + body)
                        .as(locale + " 的 " + key + " 必须带宠物名占位符 {0}")
                        .contains("{0}");
            }
        }
    }

    /**
     * {@code notify.MILESTONE_NODE.*}（无 {@code .FIRST_BIRTHDAY} 后缀）仍被
     * {@code MilestoneNotifyListener} 以 {@code args=null} 调用。那条路径不过 MessageFormat，
     * 串里一旦出现 {@code {0}}，用户会真的看到一个字面量「{0}」。
     */
    @Test
    void parameterlessMilestoneCopyStaysStatic() throws IOException {
        for (String locale : LOCALES) {
            Properties props = load(locale);
            for (String suffix : List.of("title", "body")) {
                assertThat(props.getProperty("notify.MILESTONE_NODE." + suffix))
                        .as(locale + " 的 MILESTONE_NODE." + suffix + " 不得含占位符（该路径不带参数）")
                        .isNotNull()
                        .doesNotContain("{0}");
            }
        }
    }

    private static int countPlaceholders(String raw) {
        return raw.split("\\{[0-9]}", -1).length - 1;
    }

    private static int countDoubledQuotes(String raw) {
        return raw.split("''", -1).length - 1;
    }

    private static Properties load(String locale) throws IOException {
        Properties props = new Properties();
        try (InputStream in = LifecyclePushCopyTest.class
                .getResourceAsStream("/i18n/messages_" + locale + ".properties")) {
            assertThat(in).as("messages_" + locale + ".properties 应存在").isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
