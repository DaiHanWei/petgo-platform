package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.service.NotificationCenterService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V1.4.0 gift Story 1.2 · AC5 / AC6 · L1（需 postgres + redis）：收礼通知按宠物未读即合并、每份都推（单笔文案）、
 * 已读后新起一条、两只宠物两条、主人拉黑送礼人不通知但墙照常 +N、同一人连送一条两推。
 *
 * <p>送礼走 PawCoin 渠道（同步成交 → 提交后 {@code GiftSentEvent} → {@code GiftNotifyListener}）。
 * 两只宠物用两行 {@code pet_profiles} 夹具（多宠真实场景在 ② 合入后验）。
 */
class GiftNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftSendService sendService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private UserHideRelationService hideService;
    @Autowired
    private NotificationCenterService center;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private NotificationPusher pusher;

    private GiftCatalogItem bone;

    @BeforeEach
    void seedCatalog() {
        long n = SEQ.incrementAndGet();
        bone = catalog.save(GiftCatalogItem.create("gn" + n, "Bone", "Tulang", "https://cdn/g.png", 1_000,
                false, false, false, GiftKind.NORMAL, null, 0));
    }

    private PetProfile newPet(User owner, String name) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, name, null, null, null, null,
                "GN-" + SEQ.incrementAndGet()));
    }

    private User funded() {
        User u = newUser();
        wallet.credit(u.getId(), 100_000, PawCoinTxnType.TOPUP, "TEST", null, "gift-notify-it:" + u.getId());
        return u;
    }

    private void give(User sender, PetProfile pet, int qty) {
        sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(bone.getPublicToken(), qty,
                PayChannel.PAWCOIN, "n-" + SEQ.incrementAndGet(), "gift_page", null));
    }

    private long unreadGiftRows(User owner, PetProfile pet) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'PET_GIFT_RECEIVED' AND aggregate_key = ? AND is_read = false", Long.class,
                owner.getId(), "GIFT:PET:" + pet.getId());
    }

    private List<NotificationItem> giftItems(User owner) {
        return center.list(owner.getId(), null, 20).items().stream()
                .filter(i -> "PET_GIFT_RECEIVED".equals(i.type())).toList();
    }

    @Test
    void threeSendersMergeIntoOneUnreadRowAndEachIsPushed() {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Mochi");
        User a = funded();
        User b = funded();
        User c = funded();

        give(a, pet, 1);
        give(b, pet, 1);
        give(c, pet, 5);

        assertThat(unreadGiftRows(owner, pet)).isEqualTo(1L);
        List<NotificationItem> items = giftItems(owner);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo(c.getNickname() + " dan 2 lainnya mengirim hadiah untuk Mochi");
        assertThat(items.get(0).actorCount()).isEqualTo(3);
        assertThat(items.get(0).body()).isNull();
        verify(pusher, times(3)).pushToUser(eq(owner.getId()), anyString(), eq(""), eq("PET_GIFT_RECEIVED"),
                anyString(), eq(String.valueOf(pet.getId())));
        verify(pusher).pushToUser(eq(owner.getId()), eq(c.getNickname() + " mengirim Tulang ×5 untuk Mochi"), eq(""),
                eq("PET_GIFT_RECEIVED"), anyString(), anyString());
        verify(pusher).pushToUser(eq(owner.getId()), eq(a.getNickname() + " mengirim Tulang untuk Mochi"), eq(""),
                eq("PET_GIFT_RECEIVED"), anyString(), anyString());
    }

    @Test
    void afterReadANewRowStarts() {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Mochi");
        User a = funded();
        give(a, pet, 1);
        jdbc.update("UPDATE notifications SET is_read = true, read_at = now() WHERE recipient_user_id = ?",
                owner.getId());

        give(a, pet, 1);

        assertThat(unreadGiftRows(owner, pet)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'PET_GIFT_RECEIVED'", Long.class, owner.getId())).isEqualTo(2L);
    }

    /**
     * 同一主人两只宠物按 {@code GIFT:PET:{petId}} 分开两条（gift Story 4.1：② 合入后走真实多宠送礼链路，
     * 两只宠物同属一位主人）。
     */
    @Test
    void twoPetsOfOneOwnerAreSeparateRows() {
        User owner = newUser();
        PetProfile mochi = newPet(owner, "Mochi");
        PetProfile kopi = newPet(owner, "Kopi");
        User a = funded();

        give(a, mochi, 1);
        give(a, kopi, 1);

        assertThat(unreadGiftRows(owner, mochi)).isEqualTo(1L);
        assertThat(unreadGiftRows(owner, kopi)).isEqualTo(1L);
        // 单人单种礼物 → 带礼物名（数量 1 不带 ×1）。原先期望通用文案，是因为送礼人 id ≥128 时
        // InteractionActorResolver 的 Long 引用比较恒 false、礼物名查不到（OCR #1）。
        assertThat(giftItems(owner)).extracting(NotificationItem::title).containsExactlyInAnyOrder(
                a.getNickname() + " mengirim Tulang untuk Mochi", a.getNickname() + " mengirim Tulang untuk Kopi");
    }

    @Test
    void ownerBlockingSenderGetsNoNoticeButWallStillGrows() {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Mochi");
        User a = funded();
        hideService.block(owner.getId(), a.getId());

        give(a, pet, 3);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'PET_GIFT_RECEIVED'", Long.class, owner.getId())).isZero();
        verify(pusher, never()).pushToUser(eq(owner.getId()), anyString(), anyString(), eq("PET_GIFT_RECEIVED"),
                anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT count FROM gift_pet_wall WHERE pet_id = ? AND gift_id = ?",
                Long.class, pet.getId(), bone.getId())).isEqualTo(3L);
    }

    @Test
    void sameSenderTwiceIsOneRowTwoPushes() {
        User owner = newUser();
        PetProfile pet = newPet(owner, "Mochi");
        User a = funded();

        give(a, pet, 1);
        give(a, pet, 2);

        assertThat(unreadGiftRows(owner, pet)).isEqualTo(1L);
        assertThat(giftItems(owner).get(0).actorCount()).isEqualTo(1);
        verify(pusher, times(2)).pushToUser(eq(owner.getId()), anyString(), eq(""), eq("PET_GIFT_RECEIVED"),
                anyString(), anyString());
    }
}
