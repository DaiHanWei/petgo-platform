package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.gift.web.dto.GiftOrderCard;
import com.tailtopia.gift.web.dto.GiftOrderDetail;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-4 · AC3.4。 */
class GiftOrderDetailAssemblerTest {

    private static final long SENDER = 5L;
    private static final long OWNER = 8L;
    private static final Instant AT = Instant.parse("2026-11-15T01:00:00Z");

    private static GiftOrderRow row(String message, Instant hiddenAt, Instant openedAt) {
        return new GiftOrderRow(1L, "tok", 70L, SENDER, 3L, "Bone", "Tulang", "https://cdn/g.png", 1_000, 2, AT,
                openedAt, message, hiddenAt);
    }

    private static final AuthorView ANA = new AuthorView(SENDER, "Ana", "https://cdn/a.png", false, List.of());

    @Test
    void hiddenMessageIsNullAndNotReportable() {
        GiftOrderDetail d = GiftOrderDetailAssembler.detail(row("hai", AT, null), 70L, "Mochi", OWNER, true, ANA,
                List.of("Kiki"), 40, null);
        assertThat(d.message()).isNull();
        assertThat(d.messageHidden()).isTrue();
        assertThat(d.canReport()).isFalse();
    }

    @Test
    void deletedSenderAnonymized() {
        GiftOrderDetail d = GiftOrderDetailAssembler.detail(row("hai", null, null), 70L, "Mochi", OWNER, true,
                AuthorView.anonymized(SENDER), List.of("Kiki"), 40, null);
        assertThat(d.sender().userId()).isNull();
        assertThat(d.sender().nickname()).isNull();
        assertThat(d.sender().avatarUrl()).isNull();
        assertThat(d.sender().deleted()).isTrue();
        assertThat(d.sender().petNames()).isEmpty();
        GiftOrderDetail missing = GiftOrderDetailAssembler.detail(row(null, null, null), 70L, "Mochi", OWNER, true,
                null, List.of(), 0, null);
        assertThat(missing.sender().deleted()).isTrue();
    }

    @Test
    void visitorHasNoOpenedNorOwnerExp() {
        GiftOrderDetail d = GiftOrderDetailAssembler.detail(row("hai", null, null), 70L, "Mochi", 99L, false, ANA,
                List.of(), 40, null);
        assertThat(d.opened()).isNull();
        assertThat(d.ownerExp()).isNull();
        assertThat(d.message()).as("访客也能看未拆礼物的祝福语").isEqualTo("hai");
        GiftOrderCard c = GiftOrderDetailAssembler.card(row("hai", null, null), ANA, false);
        assertThat(c.opened()).isNull();
        assertThat(c.hasMessage()).isTrue();
    }

    @Test
    void ownerSeesOpenedAndExp() {
        GiftOrderDetail d = GiftOrderDetailAssembler.detail(row(null, null, AT), 70L, "Mochi", OWNER, true, ANA,
                List.of("Kiki", "Lulu"), 40, true);
        assertThat(d.opened()).isTrue();
        assertThat(d.ownerExp()).isEqualTo(40);
        assertThat(d.alreadyOpened()).isTrue();
        assertThat(d.sender().userId()).isEqualTo(SENDER);
        assertThat(d.sender().petNames()).containsExactly("Kiki", "Lulu");
        assertThat(d.sender().avatarUrl()).contains("x-oss-process=");
        assertThat(GiftOrderDetailAssembler.card(row(null, null, null), ANA, true).opened()).isFalse();
    }

    @Test
    void canReportThreeConditions() {
        assertThat(GiftOrderDetailAssembler.detail(row("hai", null, null), 70L, "M", OWNER, true, ANA, List.of(),
                0, null).canReport()).isTrue();
        assertThat(GiftOrderDetailAssembler.detail(row(null, null, null), 70L, "M", OWNER, true, ANA, List.of(),
                0, null).canReport()).as("无祝福语").isFalse();
        assertThat(GiftOrderDetailAssembler.detail(row("hai", null, null), 70L, "M", SENDER, false, ANA, List.of(),
                null, null).canReport()).as("查看者是送礼人").isFalse();
    }
}
