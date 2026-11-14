package com.tailtopia.gift.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.gift.web.dto.GiftOrderCard;
import com.tailtopia.gift.web.dto.GiftOrderDetail;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.List;

/**
 * 逐笔卡片 / 详情的纯组装（V1.5.0 gift-pack Story 1-4 · AC1.7 / AC3，L0 可测）。
 *
 * <ul>
 *   <li>送礼人注销（或查无此人）→ nickname / avatarUrl / userId 为 null、deleted = true、petNames 为空。</li>
 *   <li>祝福语被隐藏 → message = null、messageHidden = true。</li>
 *   <li>访客视角：opened / ownerExp 为 null。</li>
 *   <li>canReport = 有祝福语 &amp;&amp; 未隐藏 &amp;&amp; 查看者 ≠ 送礼人。</li>
 * </ul>
 */
public final class GiftOrderDetailAssembler {

    private GiftOrderDetailAssembler() {
    }

    public static GiftOrderCard card(GiftOrderRow r, AuthorView sender, boolean ownerView) {
        boolean deleted = sender == null || sender.deleted();
        return new GiftOrderCard(r.publicToken(), r.giftNameEn(), r.giftNameId(), r.giftIconUrl(), r.quantity(),
                r.deliveredAt(),
                new GiftOrderCard.Sender(deleted ? null : sender.nickname(),
                        deleted ? null : avatar(sender.avatarUrl()), deleted),
                ownerView ? r.opened() : null, r.hasVisibleMessage());
    }

    /**
     * @param alreadyOpened 仅拆礼物接口传（本来就已拆）；其余 null
     * @param ownerExp      仅主人视角；访客传 null
     */
    public static GiftOrderDetail detail(GiftOrderRow r, long petId, String petName, long viewerId,
            boolean ownerView, AuthorView sender, List<String> senderPetNames, Integer ownerExp,
            Boolean alreadyOpened) {
        boolean deleted = sender == null || sender.deleted();
        boolean hidden = r.message() != null && r.messageHiddenAt() != null;
        boolean canReport = r.hasVisibleMessage() && viewerId != r.senderId();
        GiftOrderDetail.Sender s = new GiftOrderDetail.Sender(deleted ? null : r.senderId(),
                deleted ? null : sender.nickname(), deleted ? null : avatar(sender.avatarUrl()), deleted,
                deleted || senderPetNames == null ? List.of() : List.copyOf(senderPetNames));
        return new GiftOrderDetail(r.publicToken(), petId, petName, r.giftNameEn(), r.giftNameId(),
                r.giftIconUrl(), r.quantity(), r.deliveredAt(), ownerView ? r.opened() : null, alreadyOpened,
                r.hasVisibleMessage() ? r.message() : null, hidden, s, ownerView ? ownerExp : null, canReport);
    }

    private static String avatar(String url) {
        return url == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(url);
    }
}
