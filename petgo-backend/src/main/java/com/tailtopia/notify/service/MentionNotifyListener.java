package com.tailtopia.notify.service;

import com.tailtopia.content.event.ContentMentionedEvent;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 被 @ 提及的通知（V1.3.0 batch-b1 Story 3.4 · FR-119 · AD-10 Rule 5）。
 *
 * <p>跨模块经领域事件（<b>不直访 content repository</b>）：消费
 * {@link ContentMentionedEvent} → 给每个被 @ 的人发一条 {@link NotificationType#CONTENT_MENTIONED}。
 *
 * <h2>🔴 一个类型 + targetRef variant，不是两个类型（AC3/AC4）</h2>
 * <table>
 *   <tr><th></th><th>copyKey（推送文案）</th><th>targetRef</th><th>深链落点</th></tr>
 *   <tr><td>正文提及</td><td>{@code CONTENT_MENTIONED.POST}</td><td>{@code POST:{postId}}</td>
 *       <td>内容详情页</td></tr>
 *   <tr><td>评论提及</td><td>{@code CONTENT_MENTIONED.COMMENT}</td><td>{@code COMMENT:{postId}}</td>
 *       <td>内容详情页 + 锚定评论区（既有 {@code ?focus=comments}）</td></tr>
 * </table>
 * ⚠️ targetRef 里那个 id 是 <b>postId 而不是 commentId</b>：客户端要跳的是帖子详情，
 * 评论区靠锚点定位。放 commentId 的话客户端得先拿 commentId 反查 postId 才跳得动。
 *
 * <h2>🔴 AC5 拉黑不发 —— **三条判据**，任一命中即不发</h2>
 * <ol>
 *   <li><b>actor ↔ 收件人 任一方向</b>有隐藏关系（AC5 原文就是「任一方向」）。
 *       ⚠️ 只判「收件人拉黑了 actor」不够：@ 名单在写入时被 {@code MentionSanitizer}
 *       双向洗过，但通知可能晚很久才发（挂起帖过审可能是几小时后），
 *       这中间 actor 完全可以把对方拉黑。</li>
 *   <li><b>内容作者隐藏了 actor</b>（R2）—— 那条评论对所有人隐藏，被 actor @ 的第三方
 *       会收到一条点进去什么都没有的通知（同 {@code ContentNotifyListener} 的 R3 落点）。</li>
 *   <li><b>收件人隐藏了内容作者</b> —— 那条内容对他 404
 *       （{@code ContentDetailService} 按 {@code isHidden(viewer, post.authorId)} 拦）。
 *       不判的话「我拉黑了帖主」这件事会被一条 @ 通知漏掉。</li>
 * </ol>
 * 三条都<b>不区分来源</b>（主动拉黑与举报隐藏都算）—— 与主页访问校验只认 BLOCK 正好相反，别写混。
 *
 * <p>⚠️ 抑制写在本类，<b>绝不写进 {@link NotificationService#send}</b> ——
 * 那是全站唯一的通用发送方法，写进去会把封号 / 审核结果 / 内容被移除等系统通知一起掐了。
 *
 * <h2>⚠️ 查询数：一次批量 + 至多 1 + 至多 5（名单上限 5 人）</h2>
 * 第 ① 条是批量（AD-6）；第 ② 条与收件人无关，提到循环外只判一次；
 * 第 ③ 条只在**评论提及**时真的会查（正文提及里 actor 就是作者，已被 ① 覆盖）。
 */
@Component
public class MentionNotifyListener {

    /** 推送文案键前缀 —— 完整键是 {@code notify.CONTENT_MENTIONED.{POST|COMMENT}.title/body}。 */
    private static final String COPY_POST = "CONTENT_MENTIONED.POST";
    private static final String COPY_COMMENT = "CONTENT_MENTIONED.COMMENT";

    /** targetRef 的 variant 前缀（客户端据此选深链落点，见 App {@code DeepLinkRoutes}）。 */
    static final String REF_POST_PREFIX = "POST:";
    static final String REF_COMMENT_PREFIX = "COMMENT:";

    /**
     * 兜底文案（仅在 i18n 键缺失时才会被用到，且只出现在系统推送里）。
     *
     * <p>⚠️ 中文只作内部对照，**一个字都不进界面**（AC3）：通知中心条目由 App 按 type 自行
     * 本地化（en / id 双表），落库的 title/body 根本不被读取；系统推送走
     * {@code notify.CONTENT_MENTIONED.*} 的印尼语串。
     */
    private static final String FALLBACK_TITLE = "有人提到了你";
    private static final String FALLBACK_BODY = "点击查看";

    private final NotificationService notificationService;
    private final UserHideRelationReader hideRelations;

    public MentionNotifyListener(NotificationService notificationService,
            UserHideRelationReader hideRelations) {
        this.notificationService = notificationService;
        this.hideRelations = hideRelations;
    }

    @TransactionalEventListener
    public void onContentMentioned(ContentMentionedEvent event) {
        String copyKey = event.isComment() ? COPY_COMMENT : COPY_POST;
        String targetRef = (event.isComment() ? REF_COMMENT_PREFIX : REF_POST_PREFIX)
                + event.postId();
        long actorId = event.actorId();
        long contentAuthorId = event.contentAuthorId();

        // 去重保序：同一个人在一条内容里被 @ 两次只发一条（写入侧已去重，这里是双重保险）。
        Set<Long> recipients = new LinkedHashSet<>(event.mentionedUserIds());
        recipients.remove(null);
        recipients.remove(actorId); // 自己 @ 自己不推（写入侧已剔除，双重保险）
        if (recipients.isEmpty()) {
            return;
        }

        // ① AC5「**任一方向**拉黑关系 → 不发」——**一次批量、双向**判定，走 social.read
        //    的统一出口（AD-7）。
        //    🔴 只判「收件人拉黑了 actor」这一个方向是不够的：@ 名单虽然在写入时被
        //    MentionSanitizer 洗过（那次是双向的），但通知可能晚很久才发出去
        //    （挂起帖过审可能是几小时后），这中间 actor 完全可以把对方拉黑
        //    （code-review 2026-09-15）。
        Set<Long> hiddenWithActor = hideRelations.hiddenEitherWay(actorId, recipients);

        // ② R2：内容作者隐藏了 actor → 那条评论对**所有人**隐藏。
        //    与收件人无关，所以提到循环外只判一次；actor 就是作者时不必判（不会拉黑自己）。
        //    ⚠️ 漏了这条，被 actor @ 的第三方会收到一条点进去什么都没有的通知，
        //    一对比就能推断出屏蔽机制存在（同 ContentNotifyListener 的 R3 落点）。
        if (actorId != contentAuthorId && hideRelations.isHidden(contentAuthorId, actorId)) {
            return;
        }

        for (Long recipient : recipients) {
            if (hiddenWithActor.contains(recipient)) {
                continue; // ①
            }
            // ③ 🔴 收件人拉黑了**内容作者**（不是 actor）→ 那条内容对他 404
            //    （ContentDetailService 按 isHidden(viewer, post.authorId) 拦）。
            //    不判的话：C 拉黑了帖主 A，B 在 A 的帖子下评论里 @ 了 C ——
            //    C 收到通知、点进去 404，拉黑等于漏了（code-review 2026-09-15）。
            //    ⚠️ 正文提及时 actor **就是**作者，这一条与 ① 里的
            //       isHidden(recipient, actor) 完全同义 —— 所以显式跳过，不白查一次。
            if (actorId != contentAuthorId && recipient != contentAuthorId
                    && hideRelations.isHidden(recipient, contentAuthorId)) {
                continue;
            }
            // sendWithCopy：一个 type 两套文案（args=null → 静态取串）。
            // ⚠️ 用它而不是 send(...) —— send 只认 notify.<TYPE>.body 这一句静态话，
            //    那样「帖子提及」与「评论提及」就只能共用一句（AC3 明确要求分两种）。
            notificationService.sendWithCopy(recipient, NotificationType.CONTENT_MENTIONED,
                    copyKey, null, FALLBACK_TITLE, FALLBACK_BODY, targetRef);
        }
    }

}
