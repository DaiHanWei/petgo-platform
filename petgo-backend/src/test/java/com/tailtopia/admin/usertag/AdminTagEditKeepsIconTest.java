package com.tailtopia.admin.usertag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.usertag.service.AdminUserTagService;
import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import com.tailtopia.auth.repository.UserTagRepository;
import com.tailtopia.auth.service.UserTagQueryService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * L0：编辑标签时「不传新图标 = 保留原图标」（Story 11.5）。
 *
 * <p>🔴 单独测这一条的理由：图标改成上传之后，file input 为空是**常态**
 * （运营只改一个错别字）。若服务层直接把 null 写进去，那次编辑会把图标**清空** ——
 * 而后台界面上看不出来，只有 App 上图标消失才会被发现。
 */
class AdminTagEditKeepsIconTest {

    private static UserTag seed() {
        UserTag t = instantiate();
        set(t, "id", 1L);
        set(t, "code", "TOP_OWNER");
        set(t, "name", "老名字");
        set(t, "icon", "https://cdn/old.png");
        set(t, "description", "老说明");
        return t;
    }

    private static UserTag instantiate() {
        try {
            var c = UserTag.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object o, String field, Object v) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private AdminUserTagService svc(UserTag tag) {
        UserTagRepository tags = mock(UserTagRepository.class);
        when(tags.findById(1L)).thenReturn(Optional.of(tag));
        return new AdminUserTagService(tags, mock(UserTagAssignmentRepository.class),
                mock(com.tailtopia.auth.repository.UserRepository.class),
                mock(UserTagQueryService.class), mock(AdminAuditService.class));
    }

    @Test
    void nullIconKeepsTheExistingOne() {
        UserTag tag = seed();
        svc(tag).editTag(7L, 1L, "新名字", null, "新说明");

        assertThat(tag.getIcon()).as("🛡 不该被清空").isEqualTo("https://cdn/old.png");
        assertThat(tag.getName()).isEqualTo("新名字");
        assertThat(tag.getDescription()).isEqualTo("新说明");
    }

    @Test
    void newIconReplacesTheOldOne() {
        UserTag tag = seed();
        svc(tag).editTag(7L, 1L, "新名字", "https://cdn/new.png", "新说明");

        assertThat(tag.getIcon()).isEqualTo("https://cdn/new.png");
    }
}
