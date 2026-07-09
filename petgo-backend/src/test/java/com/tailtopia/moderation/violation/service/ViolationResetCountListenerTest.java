package com.tailtopia.moderation.violation.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import com.tailtopia.avatarmoderation.event.AvatarResetEvent;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.event.NameResetEvent;
import org.junit.jupiter.api.Test;

/**
 * L0（story 9 §5.1）：名称/头像违规重置事件 → 累加 NAME/AVATAR 计数（recipientUserId = 账号维度）。
 * 同步监听在发布者事务内累加（此处 mock service 验证接线正确）。
 */
class ViolationResetCountListenerTest {

    private final ViolationCountService service = mock(ViolationCountService.class);
    private final ViolationResetCountListener listener = new ViolationResetCountListener(service);

    @Test
    void nameResetRecordsNameForRecipient() {
        listener.onNameReset(new NameResetEvent(NameTargetType.PET_NAME, 42L, "card-tok"));
        verify(service).record(42L, ViolationType.NAME);
    }

    @Test
    void avatarResetRecordsAvatarForRecipient() {
        listener.onAvatarReset(new AvatarResetEvent(AvatarSubjectType.USER_AVATAR, 43L, "USER_AVATAR"));
        verify(service).record(43L, ViolationType.AVATAR);
    }
}
