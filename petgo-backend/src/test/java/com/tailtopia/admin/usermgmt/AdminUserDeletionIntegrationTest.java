package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.usermgmt.domain.DeletionType;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：删除（Story 3.3，需 Docker postgres+redis）。验证**同步**副作用：D2 先下架内容、写 USER_DELETED 审计、
 * 复用 7.3 requestDeletion 登记。级联注销本体为 @Async（7.3，本测不断言异步完成，避免 flaky）。
 */
class AdminUserDeletionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentService contentService;
    @Autowired
    private AdminAuditService auditService;

    @Test
    void d2DeleteTakesDownContentAndAudits() {
        User u = newUser();
        posts.save(ContentPost.publish(u.getId(), ContentType.DAILY, null, "违规内容", List.of()));
        long actor = 330000L + SEQ.incrementAndGet();

        adminUserService.deleteUser(u.getId(), DeletionType.VIOLATION, "色情内容", actor);

        // 内容已下架（同步）。
        assertThat(contentService.listByAuthorForAdmin(u.getId()))
                .allMatch(ContentService.PostSummary::deleted);
        // USER_DELETED 审计落库（含类型/备注，不含 PII）。
        var audits = auditService.search(null, null, actor, AuditActions.USER_DELETED, PageRequest.of(0, 5))
                .getContent();
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).getSummary()).contains("VIOLATION");
    }

    @Test
    void d1DeleteAuditsWithoutTakedown() {
        User u = newUser();
        posts.save(ContentPost.publish(u.getId(), ContentType.DAILY, null, "正常内容", List.of()));
        long actor = 340000L + SEQ.incrementAndGet();

        adminUserService.deleteUser(u.getId(), DeletionType.USER_REQUEST, "用户申请注销", actor);

        // D1 不下架内容（匿名化保留，由 7.3 异步级联处理；此处仅验未被本编排软删）。
        assertThat(contentService.listByAuthorForAdmin(u.getId()))
                .anyMatch(p -> !p.deleted());
        assertThat(auditService.search(null, null, actor, AuditActions.USER_DELETED, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }
}
