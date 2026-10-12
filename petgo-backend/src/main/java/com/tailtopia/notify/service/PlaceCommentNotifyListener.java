package com.tailtopia.notify.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.place.event.PlaceCommentApprovedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 场所评论通知（V1.3.4 follow · Story 3.2 · FR-141.7 / 141.8 / 141.12 / D-7）：消费 {@link PlaceCommentApprovedEvent}，
 * 给场所创建者发 {@code PLACE_COMMENTED}。
 *
 * <ul>
 *   <li>未读即合并：{@code aggregate_key = COMMENT:PLACE:{placeId}}（同一场所多条评论合成一条，同一人不重复计）；</li>
 *   <li>推送去抖：同一场所 10 分钟最多一条（{@code notify:placecommentpush:{placeId}}，D-7），文案取推送时刻聚合结果，
 *       <b>正文空、不含评论内容</b>；</li>
 *   <li>{@code targetRef} = 场所 publicToken；{@code comment_id} <b>留空</b>——{@code notifications.comment_id} 指向内容评论
 *       {@code comments.id}，场所评论在 {@code place_comments}、id 空间独立，填进去会被当成内容评论去查。</li>
 * </ul>
 *
 * <p>不发：评论人 == 创建者；创建者已注销或封号（D-8，{@code isActive}）；创建者是官方内容账号（D-9）；
 * 创建者隐藏了评论人（{@code isHidden}，拉黑与举报隐藏都算）。场所已不可见的情况由 place 侧不发事件（T-35）。
 */
@Component
public class PlaceCommentNotifyListener {

    private final NotificationService notificationService;
    private final UserHideRelationReader hideRelations;
    private final AccountQueryService accounts;

    public PlaceCommentNotifyListener(NotificationService notificationService, UserHideRelationReader hideRelations,
            AccountQueryService accounts) {
        this.notificationService = notificationService;
        this.hideRelations = hideRelations;
        this.accounts = accounts;
    }

    @TransactionalEventListener
    public void onPlaceCommentApproved(PlaceCommentApprovedEvent event) {
        long creator = event.placeCreatorId();
        long commenter = event.commenterId();
        if (creator == commenter) {
            return;
        }
        if (!accounts.isActive(creator) || accounts.isOfficialAccount(creator)) {
            return;
        }
        if (hideRelations.isHidden(creator, commenter)) {
            return;
        }
        notificationService.sendInteraction(creator, NotificationType.PLACE_COMMENTED, commenter,
                "COMMENT:PLACE:" + event.placeId(), event.placeToken(), null, null, null);
    }
}
