package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.service.MyGiftOrderQuery.OrderRow;
import com.tailtopia.gift.web.dto.GiftOrderPage;
import com.tailtopia.gift.web.dto.GiftOrderRow;
import com.tailtopia.shared.paging.KeysetCursor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * V1.4.0 gift Story 2.3 · AC1 · L0（mock JDBC 结果 + mock {@link GiftPetDirectory}，不起 Spring 上下文）：
 * 宠物已删 / 端口为空 → {@code petGone}；同页同一宠物只取一次；状态集合不含 EXPIRED / FAILED；
 * 分页多取一行判 nextCursor；不下发内部字段。
 */
class MyGiftOrderQueryTest {

    private static final long ME = 42L;
    private static final Instant T0 = Instant.parse("2026-10-18T10:00:00.123456Z");

    private NamedParameterJdbcTemplate jdbc;
    private GiftPetDirectory pets;
    private MyGiftOrderQuery query;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        pets = mock(GiftPetDirectory.class);
        query = new MyGiftOrderQuery(jdbc, pets);
    }

    private static OrderRow row(long id, Instant at, String status, Long petId) {
        return new OrderRow(id, "tok" + id, at, status, "QRIS", 2, 20_000L, petId, "Bone", "Tulang",
                "https://cdn/bone.png", 10_000L);
    }

    @SuppressWarnings("unchecked")
    private void returns(List<OrderRow> rows) {
        when(jdbc.query(eq(MyGiftOrderQuery.PAGE_SQL), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private MapSqlParameterSource capturedParams() {
        ArgumentCaptor<SqlParameterSource> cap = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(eq(MyGiftOrderQuery.PAGE_SQL), cap.capture(), any(RowMapper.class));
        return (MapSqlParameterSource) cap.getValue();
    }

    private static GiftPet pet(long id, String name, String avatar) {
        return new GiftPet(id, 7L, name, avatar, "CAT", null, true, false);
    }

    @Test
    void statusSetExcludesExpiredAndFailedAndQueryIsScopedToSender() {
        returns(List.of());
        GiftOrderPage page = query.page(ME, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        MapSqlParameterSource p = capturedParams();
        assertThat(p.getValue("senderId")).isEqualTo(ME);
        @SuppressWarnings("unchecked")
        List<String> statuses = (List<String>) p.getValue("statuses");
        assertThat(statuses).containsExactlyInAnyOrder("PENDING", "DELIVERED",
                "UNDELIVERED_REFUND_PENDING", "UNDELIVERED_REFUNDED", "REFUND_MANUAL")
                .doesNotContain("EXPIRED", "FAILED");
        assertThat(p.getValue("limit")).isEqualTo(MyGiftOrderQuery.PAGE_SIZE + 1);
        assertThat(MyGiftOrderQuery.PAGE_SQL).contains("sender_id = :senderId")
                .contains("ORDER BY created_at DESC, id DESC").doesNotContain("gift_catalog");
        verify(pets, never()).pet(anyLong());
    }

    @Test
    void firstPageUsesSentinelAndCursorIsPassedThrough() {
        returns(List.of());
        KeysetCursor after = new KeysetCursor(T0, 99L);
        query.page(ME, after);
        MapSqlParameterSource p = capturedParams();
        assertThat(p.getValue("afterCreatedAt")).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
        assertThat(p.getValue("afterId")).isEqualTo(99L);
    }

    @Test
    void petGoneWhenPetIdNullOrDirectoryEmptyAndSamePetFetchedOnce() {
        returns(List.of(
                row(5, T0, "DELIVERED", 30L),
                row(4, T0.minusSeconds(1), "PENDING", 30L),
                row(3, T0.minusSeconds(2), "UNDELIVERED_REFUNDED", null),
                row(2, T0.minusSeconds(3), "REFUND_MANUAL", 31L)));
        when(pets.pet(30L)).thenReturn(Optional.of(pet(30L, "Mochi", "https://cdn/p.jpg")));
        when(pets.pet(31L)).thenReturn(Optional.empty());

        GiftOrderPage page = query.page(ME, null);

        assertThat(page.items()).extracting(GiftOrderRow::orderToken).containsExactly("tok5", "tok4", "tok3", "tok2");
        GiftOrderRow first = page.items().get(0);
        assertThat(first.petGone()).isFalse();
        assertThat(first.pet().petId()).isEqualTo(30L);
        assertThat(first.pet().name()).isEqualTo("Mochi");
        assertThat(first.pet().avatarUrl()).startsWith("https://cdn/p.jpg?x-oss-process=");
        assertThat(first.status()).isEqualTo("DELIVERED");
        assertThat(first.channel()).isEqualTo("QRIS");
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.total()).isEqualTo(20_000L);
        assertThat(first.createdAt()).isEqualTo(T0);
        assertThat(first.gift()).isEqualTo(new GiftOrderRow.GiftView("Bone", "Tulang", "https://cdn/bone.png",
                10_000L));

        GiftOrderRow nullPet = page.items().get(2);
        assertThat(nullPet.pet()).isNull();
        assertThat(nullPet.petGone()).isTrue();
        assertThat(nullPet.gift().nameEn()).as("快照仍在").isEqualTo("Bone");
        GiftOrderRow emptyPort = page.items().get(3);
        assertThat(emptyPort.pet()).isNull();
        assertThat(emptyPort.petGone()).isTrue();

        verify(pets, times(1)).pet(30L);
        verify(pets, times(1)).pet(31L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void petWithoutAvatarKeepsNullAvatar() {
        returns(List.of(row(1, T0, "DELIVERED", 30L)));
        when(pets.pet(30L)).thenReturn(Optional.of(pet(30L, "Mochi", null)));
        GiftOrderRow r = query.page(ME, null).items().get(0);
        assertThat(r.pet().avatarUrl()).isNull();
        assertThat(r.petGone()).isFalse();
    }

    @Test
    void fetchesOneExtraRowToDecideNextCursor() {
        List<OrderRow> rows = new ArrayList<>();
        for (int i = 0; i < MyGiftOrderQuery.PAGE_SIZE + 1; i++) {
            rows.add(row(100 - i, T0.minusSeconds(i), "DELIVERED", null));
        }
        returns(rows);

        GiftOrderPage page = query.page(ME, null);

        assertThat(page.items()).hasSize(MyGiftOrderQuery.PAGE_SIZE);
        KeysetCursor next = KeysetCursor.decodeOrNull(page.nextCursor());
        OrderRow last = rows.get(MyGiftOrderQuery.PAGE_SIZE - 1);
        assertThat(next).isEqualTo(new KeysetCursor(last.createdAt(), last.id()));
    }

    @Test
    void exactlyOnePageHasNoNextCursor() {
        List<OrderRow> rows = new ArrayList<>();
        for (int i = 0; i < MyGiftOrderQuery.PAGE_SIZE; i++) {
            rows.add(row(100 - i, T0.minusSeconds(i), "DELIVERED", null));
        }
        returns(rows);
        assertThat(query.page(ME, null).nextCursor()).isNull();
    }

    @Test
    void rowDoesNotExposeInternalFields() {
        List<String> names = new ArrayList<>(Arrays.stream(GiftOrderRow.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
        assertThat(names).containsExactly("orderToken", "createdAt", "status", "channel", "quantity", "total",
                "gift", "pet", "petGone");
        assertThat(Arrays.stream(GiftOrderRow.GiftView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("nameEn", "nameId", "iconUrl", "unitPrice");
        assertThat(Arrays.stream(GiftOrderRow.PetView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("petId", "name", "avatarUrl");
        assertThat(MyGiftOrderQuery.PAGE_SQL).doesNotContain("payment_intent_id").doesNotContain("idem_key")
                .doesNotContain("owner_id").doesNotContain("undelivered_reason").doesNotContain("refund_error")
                .doesNotContain("pet_name");
    }
}
