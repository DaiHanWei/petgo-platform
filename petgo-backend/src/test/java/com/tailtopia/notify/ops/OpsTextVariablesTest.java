package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsTextVariables;
import com.tailtopia.ops.target.OpsTargetType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：运营通知文案变量纯函数（V1.4.2 Story 7.1 · AB-37 · D-16）。
 */
class OpsTextVariablesTest {

    private static final String NICK = OpsTextVariables.NICKNAME;
    private static final String PET = OpsTextVariables.PET_NAME;

    @Test
    @DisplayName("令牌固定、权重 20、兜底 Sobat / there")
    void constants() {
        assertThat(NICK).isEqualTo("{{nickname}}");
        assertThat(PET).isEqualTo("{{petName}}");
        assertThat(OpsTextVariables.VAR_WEIGHT).isEqualTo(20);
        assertThat(OpsTextVariables.FALLBACK_NICKNAME_ID).isEqualTo("Sobat");
        assertThat(OpsTextVariables.FALLBACK_NICKNAME_EN).isEqualTo("there");
    }

    @Test
    @DisplayName("usesPet / usesNickname：任一文本含令牌；活动看四个字段")
    void usesDetection() {
        assertThat(OpsTextVariables.usesPet("a", null, "b " + PET)).isTrue();
        assertThat(OpsTextVariables.usesPet("a " + NICK, "{petName}", "{{ petName }}")).isFalse();
        assertThat(OpsTextVariables.usesNickname(null, "x" + NICK)).isTrue();
        assertThat(OpsTextVariables.usesNickname("x" + PET)).isFalse();
        for (int i = 0; i < 4; i++) {
            String[] f = {"Judul", "Title", "Isi", "Body"};
            f[i] = f[i] + " " + PET;
            OpsNotificationCampaign c = OpsNotificationCampaign.create("tok", f[0], f[1], f[2], f[3],
                    OpsTargetType.NONE, null, AudienceSpec.all().toJson(), null, 1L);
            assertThat(OpsTextVariables.usesPet(c)).as("字段 " + i).isTrue();
        }
        OpsNotificationCampaign none = OpsNotificationCampaign.create("tok", "Judul " + NICK, "Title", "Isi",
                "Body", OpsTargetType.NONE, null, AudienceSpec.all().toJson(), null, 1L);
        assertThat(OpsTextVariables.usesPet(none)).isFalse();
    }

    @Test
    @DisplayName("countedLength：每个令牌按 20，其余按 length()（PRD AC6 / UI 稿 I1 例）")
    void countedLength() {
        assertThat(OpsTextVariables.countedLength("x".repeat(115) + NICK)).isEqualTo(135);
        assertThat(OpsTextVariables.countedLength("x".repeat(100) + PET)).isEqualTo(120);
        assertThat(OpsTextVariables.countedLength("Hi " + NICK + "! " + PET + " has new friends waiting"))
                .isEqualTo(69);
        assertThat(OpsTextVariables.countedLength(NICK + NICK + PET)).isEqualTo(60);
        assertThat(OpsTextVariables.countedLength("{nickname} {{pet}}")).isEqualTo(18);
        assertThat(OpsTextVariables.countedLength(null)).isZero();
        assertThat(OpsTextVariables.countedLength("宠物🐶")).isEqualTo("宠物🐶".length());
    }

    @Test
    @DisplayName("render：两令牌多次出现全替换；空 / 空白昵称 → ID Sobat、EN there；值原样插入")
    void render() {
        String t = "Hi " + NICK + ", " + PET + " & " + PET + " · " + NICK;
        assertThat(OpsTextVariables.render(t, "hex", "tuotuo", true)).isEqualTo("Hi hex, tuotuo & tuotuo · hex");
        assertThat(OpsTextVariables.render("Halo " + NICK, null, null, false)).isEqualTo("Halo Sobat");
        assertThat(OpsTextVariables.render("Halo " + NICK, "   ", null, false)).isEqualTo("Halo Sobat");
        assertThat(OpsTextVariables.render("Hi " + NICK, "", null, true)).isEqualTo("Hi there");
        // 原样插入：$ / \ / 令牌字样都不被解释、不二次替换
        assertThat(OpsTextVariables.render(NICK + "|" + PET, "a$1\\b", PET, true)).isEqualTo("a$1\\b|" + PET);
        assertThat(OpsTextVariables.render("<b>" + PET + "</b>", "x", "<i>&", false)).isEqualTo("<b><i>&</b>");
        assertThat(OpsTextVariables.render("Promo", "hex", null, true)).isEqualTo("Promo");
        assertThat(OpsTextVariables.render(null, "hex", "p", true)).isNull();
    }

    @Test
    @DisplayName("render：含宠物令牌而宠物名缺失 → IllegalArgumentException")
    void renderRequiresPet() {
        assertThatThrownBy(() -> OpsTextVariables.render("Hi " + PET, "hex", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("containsTyped：六个显示名 × 半角 / 全角 / 大小写 / 括号内空白均命中")
    void typedDisplayNames() {
        for (String name : OpsTextVariables.DISPLAY_NAMES) {
            for (String v : List.of("{" + name + "}", "｛" + name + "｝", "{ " + name + "　}",
                    "x{" + name.toUpperCase() + "}y", "{" + name.toLowerCase() + "}", "｛ " + name + " }")) {
                assertThat(OpsTextVariables.containsTyped(v)).as(v).isTrue();
            }
            assertThat(OpsTextVariables.containsTyped(name)).as("不带括号不拦：" + name).isFalse();
        }
        assertThat(OpsTextVariables.containsTyped("{Pet   name}")).isTrue();
        assertThat(OpsTextVariables.DISPLAY_NAMES).hasSize(6);
    }

    @Test
    @DisplayName("containsTyped：合法令牌不命中；其它 {{…}} 命中；普通花括号文本不命中")
    void typedTokens() {
        assertThat(OpsTextVariables.containsTyped("Hi " + NICK + " & " + PET)).isFalse();
        assertThat(OpsTextVariables.containsTyped("{{Nickname}}")).isTrue();
        assertThat(OpsTextVariables.containsTyped("{{pet}}")).isTrue();
        assertThat(OpsTextVariables.containsTyped("{{ nickname }}")).isTrue();
        assertThat(OpsTextVariables.containsTyped("{{}}")).isTrue();
        assertThat(OpsTextVariables.containsTyped("Diskon {20%} {promo} { }")).isFalse();
        assertThat(OpsTextVariables.containsTyped("{{nickname")).isFalse();
        assertThat(OpsTextVariables.containsTyped((String) null)).isFalse();
        assertThat(OpsTextVariables.containsTyped("a", null, "{用户昵称}")).isTrue();
        assertThat(OpsTextVariables.containsTyped("a", null, NICK)).isFalse();
    }

    @Test
    @DisplayName("toDisplay：令牌换成显示名，其余原样")
    void toDisplay() {
        assertThat(OpsTextVariables.toDisplay("Hi " + NICK + ", " + PET + "!", "{用户昵称}", "{宠物昵称}"))
                .isEqualTo("Hi {用户昵称}, {宠物昵称}!");
        assertThat(OpsTextVariables.toDisplay("Promo", "{a}", "{b}")).isEqualTo("Promo");
        assertThat(OpsTextVariables.toDisplay(null, "{a}", "{b}")).isNull();
    }
}
