package com.tailtopia.moderation.violation.service;

import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.avatarmoderation.event.AvatarResetEvent;
import com.tailtopia.namemoderation.event.NameResetEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 名称/头像违规重置 → 违规计数累加监听（内容审核 story 9，§5.1 计入 NAME/AVATAR）。
 *
 * <p>cm-4/5 在运营判 VIOLATION、重置默认名/头像后发 {@link NameResetEvent}/{@link AvatarResetEvent}
 * （事件注释即写明"供 story 9 违规计数订阅"）。本监听以<b>同步 {@code @EventListener}</b> 接收——运行在
 * 发布者（reset 处置）的<b>同一事务</b>内，处置回滚则计数不落（§5.2 同事务一致，AC-6）；<b>非</b>
 * {@code @TransactionalEventListener(AFTER_COMMIT)}（那是 notify 的推送时机，与本计入解耦）。
 *
 * <p>{@code recipientUserId} = 违规内容所属账号 users.id（昵称=本人、宠物名/头像=owner），即计数账号维度。
 * 累加只经 {@link ViolationCountService#record}（仅记录不处置）。
 */
@Component
public class ViolationResetCountListener {

    private final ViolationCountService violationCounts;

    public ViolationResetCountListener(ViolationCountService violationCounts) {
        this.violationCounts = violationCounts;
    }

    @EventListener
    public void onNameReset(NameResetEvent event) {
        violationCounts.record(event.recipientUserId(), ViolationType.NAME);
    }

    @EventListener
    public void onAvatarReset(AvatarResetEvent event) {
        violationCounts.record(event.recipientUserId(), ViolationType.AVATAR);
    }
}
