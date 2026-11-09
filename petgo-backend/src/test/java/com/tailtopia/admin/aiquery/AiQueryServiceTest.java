package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import com.tailtopia.admin.aiquery.service.AiQueryRejection;
import com.tailtopia.admin.aiquery.service.AiQueryService;
import com.tailtopia.admin.aiquery.service.AiQueryTokens;
import com.tailtopia.admin.audit.service.AdminAuditService;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：提交校验、标题截取、token 格式。 */
class AiQueryServiceTest {

    private AiQueryService service(boolean enabled) {
        AiQueryProperties p = new AiQueryProperties();
        p.setMode(enabled ? "on" : "off");
        return new AiQueryService(mock(AiQueryConversationRepository.class), mock(AiQueryMessageRepository.class),
                mock(AdminAuditService.class), p, mock(EntityManager.class));
    }

    private static AiQueryRejection.Reason reasonOf(Runnable r) {
        try {
            r.run();
        } catch (AiQueryRejection e) {
            return e.reason();
        }
        return null;
    }

    @Test
    void validation() {
        AiQueryService on = service(true);
        assertThat(reasonOf(() -> on.startConversation(1, null, "zh"))).isEqualTo(AiQueryRejection.Reason.EMPTY);
        assertThat(reasonOf(() -> on.startConversation(1, " \n\t ", "zh"))).isEqualTo(AiQueryRejection.Reason.EMPTY);
        assertThat(reasonOf(() -> on.startConversation(1, "x".repeat(2001), "zh"))).isEqualTo(AiQueryRejection.Reason.TOO_LONG);
        // 按码点计：2000 个 emoji 不算超长
        assertThatThrownBy(() -> service(true).startConversation(1, "😀".repeat(2001), "zh"))
                .isInstanceOf(AiQueryRejection.class)
                .satisfies(e -> assertThat(((AiQueryRejection) e).args()[0]).isEqualTo(2001));
        assertThat(reasonOf(() -> service(false).startConversation(1, "q", "zh"))).isEqualTo(AiQueryRejection.Reason.DISABLED);
        // 2000 个 emoji（4000 个 UTF-16 单元）不算超长：按码点计
        assertThat(on.validateForTest("😀".repeat(2000))).hasSize(4000);
    }

    @Test
    void titleIsFirst80CodePointsFlattened() {
        String q = "第一行\n第二行  " + "字".repeat(100);
        AiQueryConversation c = AiQueryConversation.start("t", 1, q, AiQueryLanguage.ZH);
        assertThat(c.getTitle()).startsWith("第一行 第二行 字").hasSize(80);
        AiQueryConversation e = AiQueryConversation.start("t", 1, "😀".repeat(100), AiQueryLanguage.ZH);
        assertThat(e.getTitle().codePointCount(0, e.getTitle().length())).isEqualTo(80);
    }

    @Test
    void tokensAreUnguessableAndAgentCompatible() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = AiQueryTokens.newToken();
            assertThat(t).matches("[A-Za-z0-9_-]{32}");
            assertThat(seen.add(t)).isTrue();
        }
        assertThat(AiQueryTokens.isValid("../../etc/passwd")).isFalse();
        assertThat(AiQueryTokens.isValid("short")).isFalse();
    }
}
