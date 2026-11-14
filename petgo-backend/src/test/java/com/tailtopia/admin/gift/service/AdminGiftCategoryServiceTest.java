package com.tailtopia.admin.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftCategoryForm;
import com.tailtopia.admin.gift.dto.GiftCategoryRow;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftCategoryRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 gift-pack Story 1-2 · AC1（Mockito）：重名（EN / ID、含已下线、大小写）、有在线礼物不能下线、排序越界。 */
class AdminGiftCategoryServiceTest {

    private static final long ADMIN = 5L;

    private GiftCategoryRepository categories;
    private GiftCatalogRepository catalog;
    private AdminAuditService audit;
    private AdminGiftCategoryService service;
    private final List<GiftCategory> all = new ArrayList<>();

    private static GiftCategory cat(long id, String en, String idName, int sort, boolean online) {
        GiftCategory c = GiftCategory.create(en, idName, sort, null);
        ReflectionTestUtils.setField(c, "id", id);
        if (!online) {
            c.setStatus(GiftCategoryStatus.OFFLINE, null);
        }
        return c;
    }

    @BeforeEach
    void setUp() {
        categories = mock(GiftCategoryRepository.class);
        catalog = mock(GiftCatalogRepository.class);
        audit = mock(AdminAuditService.class);
        all.add(cat(1, "Snacks", "Camilan", 10, true));
        all.add(cat(2, "Seasonal", "Musiman", 60, false));
        when(categories.findAll()).thenReturn(all);
        when(categories.findAllByOrderBySortOrderAscIdAsc()).thenReturn(all);
        for (GiftCategory c : all) {
            when(categories.findById(c.getId())).thenReturn(Optional.of(c));
        }
        when(categories.saveAndFlush(any(GiftCategory.class))).thenAnswer(inv -> {
            GiftCategory c = inv.getArgument(0);
            if (c.getId() == null) {
                ReflectionTestUtils.setField(c, "id", 9L);
            }
            return c;
        });
        service = new AdminGiftCategoryService(categories, catalog, audit);
    }

    private static String codeOf(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    @Test
    void duplicateEnIgnoringCaseIncludingOffline() {
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("snacks", "Baru", "70")))
                .satisfies(t -> {
                    assertThat(codeOf(t)).isEqualTo("admin.v150.giftCategory.err.duplicate");
                    assertThat(((AppException) t).getMessageArgs()).containsExactly("EN", "snacks", "Snacks");
                });
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("SEASONAL", "Baru", "70")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.v150.giftCategory.err.duplicate"));
        verify(categories, never()).saveAndFlush(any());
    }

    @Test
    void duplicateIdName() {
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("Toys", " musiman ", "70")))
                .satisfies(t -> assertThat(((AppException) t).getMessageArgs())
                        .containsExactly("ID", "musiman", "Seasonal"));
    }

    @Test
    void editKeepingOwnNameIsNotDuplicate() {
        assertThat(service.update(ADMIN, 1L, new GiftCategoryForm("Snacks", "Camilan", "15"))).isTrue();
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATEGORY_UPDATED), eq("gift_categories"), eq("1"),
                eq("sort_order 10→15"));
        assertThat(service.update(ADMIN, 1L, new GiftCategoryForm("Snacks", "Camilan", "15"))).isFalse();
    }

    @Test
    void sortOrderOutOfRangeAndNameRules() {
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("Toys", "Mainan", "10000")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.err.gift.sortOrderRange"));
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("Toys", "Mainan", "-1")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.err.gift.sortOrderRange"));
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("Toys", "Mainan", "1.5")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.err.gift.sortOrderRange"));
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm(" ", "Mainan", "1")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.err.gift.nameRequired"));
        assertThatThrownBy(() -> service.create(ADMIN, new GiftCategoryForm("x".repeat(41), "Mainan", "1")))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.err.gift.nameTooLong"));
    }

    @Test
    void createTrimsAndAudits() {
        long id = service.create(ADMIN, new GiftCategoryForm(" Toys ", " Mainan ", ""));
        assertThat(id).isEqualTo(9L);
        verify(audit).record(eq(ADMIN), eq(AuditActions.GIFT_CATEGORY_CREATED), eq("gift_categories"), eq("9"),
                eq("name_en=Toys; name_id=Mainan; sort_order=0"));
    }

    @Test
    void cannotOfflineWithOnlineGifts() {
        when(catalog.countByCategoryIdAndStatus(1L, GiftCatalogStatus.ONLINE)).thenReturn(8L);
        assertThatThrownBy(() -> service.setOnline(ADMIN, 1L, false))
                .satisfies(t -> {
                    assertThat(codeOf(t)).isEqualTo("admin.v150.giftCategory.offlineBlocked");
                    assertThat(((AppException) t).getMessageArgs()).containsExactly(8L);
                });
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void offlineAndOnlineIdempotent() {
        when(catalog.countByCategoryIdAndStatus(1L, GiftCatalogStatus.ONLINE)).thenReturn(0L);
        assertThat(service.setOnline(ADMIN, 1L, false)).isTrue();
        assertThat(service.setOnline(ADMIN, 1L, false)).isFalse();
        assertThat(service.setOnline(ADMIN, 1L, true)).isTrue();
        verify(audit, times(1)).record(eq(ADMIN), eq(AuditActions.GIFT_CATEGORY_OFFLINED), anyString(), eq("1"),
                anyString());
        verify(audit, times(1)).record(eq(ADMIN), eq(AuditActions.GIFT_CATEGORY_ONLINED), anyString(), eq("1"),
                anyString());
    }

    @Test
    void listCountsOnlineAndTotalAndHiddenInApp() {
        GiftCatalogItem on = GiftCatalogItem.create("t1", "A", "A", "u", 1000, false, false, false, GiftKind.NORMAL, null, 0);
        on.setCategoryId(1L);
        GiftCatalogItem off = GiftCatalogItem.create("t2", "B", "B", "u", 1000, false, false, false, GiftKind.NORMAL, null, 0);
        off.setCategoryId(1L);
        off.setStatus(GiftCatalogStatus.OFFLINE, null);
        all.add(cat(3, "Toys", "Mainan", 30, true));
        when(catalog.findAll()).thenReturn(List.of(on, off));
        List<GiftCategoryRow> rows = service.list();
        assertThat(rows.get(0).onlineGifts()).isEqualTo(1);
        assertThat(rows.get(0).totalGifts()).isEqualTo(2);
        assertThat(rows.get(0).hiddenInApp()).isFalse();
        assertThat(rows.get(2).hiddenInApp()).as("在线但 0 个在线礼物").isTrue();
        assertThat(rows.get(1).hiddenInApp()).as("已下线不算").isFalse();
        assertThat(rows.get(2).code()).isEqualTo("cat-3");
    }

    @Test
    void missingCategoryIs404() {
        when(categories.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(404L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("admin.v150.giftCategory.err.notFound"));
    }
}
