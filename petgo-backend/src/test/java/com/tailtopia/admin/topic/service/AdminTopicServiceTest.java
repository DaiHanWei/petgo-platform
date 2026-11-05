package com.tailtopia.admin.topic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.topic.service.AdminTopicService.NameCheck;
import com.tailtopia.admin.topic.service.AdminTopicStore.FeaturedSlot;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * L0（V1.4.2 Story 6.1）：后台话题服务规则 —— 仓储 / 顶置 / 审计全部 mock，无 Spring、无 DB。
 * 真库并发与 SQL 口径在 L1 {@code AdminTopicIntegrationTest}。
 */
class AdminTopicServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    private static final long ADMIN = 7L;

    private AdminTopicStore store;
    private ContentPinService pins;
    private AdminAuditService audit;
    private AdminTopicService service;

    @BeforeEach
    void setUp() {
        store = mock(AdminTopicStore.class);
        pins = mock(ContentPinService.class);
        audit = mock(AdminAuditService.class);
        service = new AdminTopicService(store, pins, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        // 默认：状态守卫命中（1 行）；并发用例里单独改成 0
        when(store.updateStatus(anyLong(), any(), any(), any(), any())).thenReturn(1);
    }

    private static TopicView topic(long id, String display, TopicType type, TopicStatus status, Integer featured) {
        return new TopicView(id, display, display.toLowerCase(java.util.Locale.ROOT), type, status,
                type == TopicType.OFFICIAL ? "desc id" : null, null, 10, new BigDecimal("12.5"), featured,
                status == TopicStatus.DISABLED ? NOW.minusSeconds(60) : null, NOW.minusSeconds(3600));
    }

    private static void assertCode(Throwable t, String code) {
        assertThat(t).isInstanceOf(AppException.class);
        assertThat(((AppException) t).getMessageCode()).isEqualTo(code);
    }

    private String auditSummary(String action) {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ADMIN), eq(action), eq("topic"), anyString(), summary.capture());
        return summary.getValue();
    }

    // ================================================================ checkName

    @Nested
    class CheckName {

        @ParameterizedTest
        @ValueSource(strings = {"a", "abcdefghijklmnopqrstuvwxyz12345", "12345", "ab cd", "kucing😺", "#kucing", "kucing-lucu"})
        void invalidNames(String raw) {
            assertThat(service.checkName(raw).kind()).isEqualTo(NameCheck.Kind.INVALID);
            verify(store, never()).findByNorm(anyString());
        }

        @Test
        void blankIsNull() {
            assertThat(service.checkName("  ")).isNull();
            assertThat(service.checkName(null)).isNull();
        }

        @Test
        void newName() {
            when(store.findByNorm("kucinglucu")).thenReturn(Optional.empty());
            assertThat(service.checkName("KucingLucu").kind()).isEqualTo(NameCheck.Kind.NEW);
        }

        @Test
        void sameNameUserActiveIsUpgradeWithLiveCount() {
            when(store.findByNorm("kucinglucu")).thenReturn(Optional.of(topic(3, "kucingLucu", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.livePostCount(3)).thenReturn(42);
            NameCheck c = service.checkName("KUCINGLUCU"); // 大小写不同视为同名
            assertThat(c.kind()).isEqualTo(NameCheck.Kind.UPGRADE);
            assertThat(c.livePostCount()).isEqualTo(42);
            assertThat(c.nameDisplay()).isEqualTo("kucingLucu");
        }

        @Test
        void userHiddenIsStillUpgrade() {
            when(store.findByNorm("giveaway")).thenReturn(Optional.of(topic(4, "Giveaway", TopicType.USER, TopicStatus.HIDDEN, null)));
            NameCheck c = service.checkName("giveaway");
            assertThat(c.kind()).isEqualTo(NameCheck.Kind.UPGRADE);
            assertThat(c.status()).isEqualTo(TopicStatus.HIDDEN);
        }

        @Test
        void userDisabledConflicts() {
            when(store.findByNorm("jualkucing")).thenReturn(Optional.of(topic(5, "JualKucing", TopicType.USER, TopicStatus.DISABLED, null)));
            assertThat(service.checkName("JualKucing").kind()).isEqualTo(NameCheck.Kind.DISABLED_CONFLICT);
        }

        @Test
        void officialExists() {
            when(store.findByNorm("tipsgrooming")).thenReturn(Optional.of(topic(6, "TipsGrooming", TopicType.OFFICIAL, TopicStatus.ACTIVE, 1)));
            assertThat(service.checkName("tipsgrooming").kind()).isEqualTo(NameCheck.Kind.OFFICIAL_EXISTS);
        }
    }

    // ================================================================ create

    @Nested
    class Create {

        @Test
        void invalidNameIs422WithoutWrites() {
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.create(ADMIN, "1", null, null, false));
            assertCode(t, "admin.err.topics.nameInvalid");
            verifyNoInteractions(audit);
            verify(store, never()).insertOfficial(any(), any(), any(), any());
        }

        @Test
        void newOfficialTopic() {
            TopicView created = topic(9, "TipsMakanan", TopicType.OFFICIAL, TopicStatus.ACTIVE, null);
            when(store.findByNorm("tipsmakanan")).thenReturn(Optional.empty(), Optional.of(created));
            when(store.insertOfficial("TipsMakanan", "tipsmakanan", "Halo", null)).thenReturn(true);

            var r = service.create(ADMIN, " TipsMakanan ", " Halo ", "  ", false);

            assertThat(r.id()).isEqualTo(9);
            assertThat(r.upgraded()).isFalse();
            assertThat(auditSummary(AuditActions.TOPIC_CREATED)).contains("tipsmakanan").doesNotContain("Halo");
        }

        @Test
        void upgradeWithoutConfirmationIs422AndEchoesWarning() {
            when(store.findByNorm("kucingoren")).thenReturn(Optional.of(topic(3, "kucingoren", TopicType.USER, TopicStatus.ACTIVE, 4)));
            when(store.livePostCount(3)).thenReturn(87);
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "KucingOren", null, null, false));
            assertCode(t, "admin.topics.upgradeWarn");
            assertThat(((AppException) t).getMessageArgs()).containsExactly("kucingoren", 87);
            verify(store, never()).upgrade(anyLong(), any(), any(), any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        void confirmedUpgradeKeepsStatusAndFeaturedPosition() {
            when(store.findByNorm("kucingoren")).thenReturn(Optional.of(topic(3, "kucingoren", TopicType.USER, TopicStatus.ACTIVE, 4)));
            when(store.livePostCount(3)).thenReturn(87);
            when(store.upgrade(3, "KucingOren", "desc", null, NOW)).thenReturn(true);

            var r = service.create(ADMIN, "KucingOren", "desc", null, true);

            assertThat(r.upgraded()).isTrue();
            assertThat(r.postCount()).isEqualTo(87);
            assertThat(r.nameDisplay()).isEqualTo("KucingOren");
            verify(store, never()).updateStatus(anyLong(), any(), any(), any(), any());
            verify(store, never()).setFeaturedOrder(anyLong(), any());
            assertThat(auditSummary(AuditActions.TOPIC_UPGRADED)).contains("USER -> OFFICIAL").doesNotContain("desc");
        }

        @Test
        void hiddenUserTopicUpgradesAndStaysHidden() {
            when(store.findByNorm("giveaway")).thenReturn(Optional.of(topic(4, "giveaway", TopicType.USER, TopicStatus.HIDDEN, null)));
            when(store.upgrade(4, "Giveaway", null, null, NOW)).thenReturn(true);

            service.create(ADMIN, "Giveaway", null, null, true);

            verify(store).upgrade(4, "Giveaway", null, null, NOW);
            verify(store, never()).updateStatus(anyLong(), any(), any(), any(), any()); // R-5：保持 HIDDEN
            assertThat(auditSummary(AuditActions.TOPIC_UPGRADED)).contains("status=HIDDEN");
        }

        @Test
        void disabledUserTopicRejectsUpgradeWithZeroWrites() {
            when(store.findByNorm("jualkucing")).thenReturn(Optional.of(topic(5, "JualKucing", TopicType.USER, TopicStatus.DISABLED, null)));
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "JualKucing", null, null, true));
            assertCode(t, "admin.err.topics.upgradeDisabled");
            verify(store, never()).upgrade(anyLong(), any(), any(), any(), any());
            verify(store, never()).insertOfficial(any(), any(), any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        void officialDuplicateIs422() {
            when(store.findByNorm("tipsgrooming")).thenReturn(Optional.of(topic(6, "TipsGrooming", TopicType.OFFICIAL, TopicStatus.ACTIVE, null)));
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "TipsGrooming", null, null, true));
            assertCode(t, "admin.err.topics.officialExists");
            verifyNoInteractions(audit);
        }

        @Test
        void concurrentInsertLostFallsBackToUpgradeCheck() {
            // 查时没有 → 插入时被 App 发帖抢先（DO NOTHING）→ 重查是 USER → 未确认 → 422 黄条同句
            when(store.findByNorm("kucingbaru")).thenReturn(Optional.empty(),
                    Optional.of(topic(11, "kucingbaru", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.insertOfficial(any(), any(), any(), any())).thenReturn(false);
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "KucingBaru", null, null, false));
            assertCode(t, "admin.topics.upgradeWarn");
        }

        @Test
        void concurrentOfficialInsertLostIsOfficialExists() {
            when(store.findByNorm("promo")).thenReturn(Optional.empty(),
                    Optional.of(topic(12, "Promo", TopicType.OFFICIAL, TopicStatus.ACTIVE, null)));
            when(store.insertOfficial(any(), any(), any(), any())).thenReturn(false);
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "Promo", null, null, false));
            assertCode(t, "admin.err.topics.officialExists");
        }

        @Test
        void descriptionLengthCountsCodePointsNotUtf16Units() {
            // 500 个补充平面字符（UTF-16 长 1000）仍合法：口径与 PG VARCHAR(500) 字符数一致
            TopicView created = topic(13, "Emoji", TopicType.OFFICIAL, TopicStatus.ACTIVE, null);
            when(store.findByNorm("emoji")).thenReturn(Optional.empty(), Optional.of(created));
            when(store.insertOfficial(any(), any(), any(), any())).thenReturn(true);
            String emoji500 = "😺".repeat(500);
            service.create(ADMIN, "Emoji", emoji500, null, false);
            verify(store).insertOfficial("Emoji", "emoji", emoji500, null);
            assertCode(org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "Emoji", "😺".repeat(501), null, false)), "admin.err.topics.descTooLong");
        }

        @Test
        void descriptionTooLongIs422() {
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.create(ADMIN, "Promo", "x".repeat(501), null, false));
            assertCode(t, "admin.err.topics.descTooLong");
        }
    }

    // ================================================================ update

    @Nested
    class Update {

        @Test
        void caseOnlyChangePasses() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "mypet", TopicType.USER, TopicStatus.ACTIVE, null)));
            assertThat(service.update(ADMIN, 1, "MyPet", null, null)).isTrue();
            verify(store).updateContent(1, "MyPet", null, null, NOW);
            assertThat(auditSummary(AuditActions.TOPIC_UPDATED)).contains("mypet -> MyPet");
        }

        @ParameterizedTest
        @ValueSource(strings = {"mype", "mypets", "mybet", "my pet", "", "12"})
        void anythingButCaseIs422(String candidate) {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "mypet", TopicType.USER, TopicStatus.ACTIVE, null)));
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.update(ADMIN, 1, candidate, null, null));
            assertCode(t, "admin.err.topics.caseOnly");
            verify(store, never()).updateContent(anyLong(), any(), any(), any(), any());
        }

        @Test
        void userTopicIgnoresDescriptions() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "mypet", TopicType.USER, TopicStatus.ACTIVE, null)));
            service.update(ADMIN, 1, "MyPet", "injected", "injected");
            verify(store).updateContent(1, "MyPet", null, null, NOW);
        }

        @Test
        void userTopicDescriptionOnlyIsNoop() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "mypet", TopicType.USER, TopicStatus.ACTIVE, null)));
            assertThat(service.update(ADMIN, 1, "mypet", "injected", null)).isFalse();
            verify(store, never()).updateContent(anyLong(), any(), any(), any(), any());
            verifyNoInteractions(audit);
        }

        @Test
        void officialDescriptionChangeIsAuditedWithoutText() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "KucingLucu", TopicType.OFFICIAL, TopicStatus.ACTIVE, null)));
            service.update(ADMIN, 2, "KucingLucu", "baru sekali", "new");
            verify(store).updateContent(2, "KucingLucu", "baru sekali", "new", NOW);
            assertThat(auditSummary(AuditActions.TOPIC_UPDATED)).contains("description changed")
                    .doesNotContain("baru sekali").doesNotContain("new");
        }
    }

    // ================================================================ 首页话题行

    @Nested
    class Featured {

        @Test
        void featureAppendsAtNPlusOneUnderLock() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "a1", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.featuredCount()).thenReturn(3);
            assertThat(service.feature(ADMIN, 1)).isEqualTo(4);
            InOrder o = inOrder(store);
            o.verify(store).lockFeatured();
            o.verify(store).setFeaturedOrder(1, 4);
            assertThat(auditSummary(AuditActions.TOPIC_FEATURED)).contains("order=4");
        }

        @Test
        void featureWhenFullIs422() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "a1", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.featuredCount()).thenReturn(5);
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.feature(ADMIN, 1));
            assertCode(t, "admin.err.topics.featureLimit");
            verify(store, never()).setFeaturedOrder(anyLong(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"HIDDEN", "DISABLED"})
        void hiddenOrDisabledCannotBeFeatured(String status) {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "a1", TopicType.USER, TopicStatus.valueOf(status), null)));
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.feature(ADMIN, 1));
            assertCode(t, "admin.err.topics.featureNotAllowed");
            verify(store).lockFeatured();
        }

        @Test
        void featureIsIdempotent() {
            when(store.findById(1)).thenReturn(Optional.of(topic(1, "a1", TopicType.USER, TopicStatus.ACTIVE, 2)));
            assertThat(service.feature(ADMIN, 1)).isEqualTo(2);
            verify(store, never()).setFeaturedOrder(anyLong(), any());
            verifyNoInteractions(audit);
        }

        @Test
        void unfeatureSecondShiftsFollowersUpInAscendingOrder() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, 2)));
            when(store.featuredAfter(2)).thenReturn(List.of(new FeaturedSlot(3, 3), new FeaturedSlot(4, 4), new FeaturedSlot(5, 5)));
            service.unfeature(ADMIN, 2);
            InOrder o = inOrder(store);
            o.verify(store).lockFeatured();
            o.verify(store).lockFeaturedRows(); // 按 id 锁行在任何逐行改序号之前（防与热度任务死锁）
            o.verify(store).setFeaturedOrder(2, null);
            o.verify(store).setFeaturedOrder(3, 2);
            o.verify(store).setFeaturedOrder(4, 3);
            o.verify(store).setFeaturedOrder(5, 4);
            assertThat(auditSummary(AuditActions.TOPIC_UNFEATURED)).contains("order=2");
        }

        @Test
        void reorderSwapsInThreeSteps() {
            when(store.findById(3)).thenReturn(Optional.of(topic(3, "a3", TopicType.USER, TopicStatus.ACTIVE, 3)));
            when(store.featuredCount()).thenReturn(4);
            when(store.idAtFeaturedOrder(1)).thenReturn(Optional.of(8L));
            service.reorder(ADMIN, 3, 1);
            InOrder o = inOrder(store);
            o.verify(store).lockFeatured();
            o.verify(store).lockFeaturedRows();
            o.verify(store).setFeaturedOrder(3, null);
            o.verify(store).setFeaturedOrder(8, 3);
            o.verify(store).setFeaturedOrder(3, 1);
            assertThat(auditSummary(AuditActions.TOPIC_FEATURE_REORDERED)).contains("3 -> 1");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 5, -1})
        void reorderOutOfRangeCarriesCount(int order) {
            when(store.findById(3)).thenReturn(Optional.of(topic(3, "a3", TopicType.USER, TopicStatus.ACTIVE, 3)));
            when(store.featuredCount()).thenReturn(4);
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> service.reorder(ADMIN, 3, order));
            assertCode(t, "admin.err.topics.featureOrderRange");
            assertThat(((AppException) t).getMessageArgs()).containsExactly(4);
            verify(store, never()).setFeaturedOrder(anyLong(), any());
        }
    }

    // ================================================================ 隐藏 / 禁用 / 恢复

    @Nested
    class Status {

        @Test
        void hideFeaturedTopicRemovesItFirstThenChangesStatus() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, 2)));
            when(store.featuredAfter(2)).thenReturn(List.of(new FeaturedSlot(3, 3)));
            assertThat(service.hide(ADMIN, 2)).isTrue();
            InOrder o = inOrder(store);
            o.verify(store).lockFeatured();
            o.verify(store).lockFeaturedRows();
            o.verify(store).setFeaturedOrder(2, null);
            o.verify(store).setFeaturedOrder(3, 2);
            o.verify(store).updateStatus(2, TopicStatus.ACTIVE, TopicStatus.HIDDEN, null, NOW); // ck_topics_featured_active：先移出再改状态
            assertThat(auditSummary(AuditActions.TOPIC_HIDDEN)).contains("ACTIVE -> HIDDEN").contains("unfeatured order=2");
            verifyNoInteractions(pins);
        }

        @Test
        void hideNonFeatured() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, null)));
            assertThat(service.hide(ADMIN, 2)).isFalse();
            verify(store, never()).setFeaturedOrder(anyLong(), any());
        }

        @Test
        void hideDisabledIsStale() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.DISABLED, null)));
            assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.hide(ADMIN, 2)), "admin.err.topics.stale");
            verify(store, never()).updateStatus(anyLong(), any(), any(), any(), any());
        }

        @Test
        void disableWritesDisabledAtUnfeaturesAndEndsTopicPins() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.OFFICIAL, TopicStatus.ACTIVE, 1)));
            when(store.featuredAfter(1)).thenReturn(List.of());
            when(pins.terminateForSlot("TOPIC:2", NOW)).thenReturn(2);
            service.disable(ADMIN, 2);
            InOrder o = inOrder(store, pins);
            o.verify(store).lockFeatured();
            o.verify(store).setFeaturedOrder(2, null);
            o.verify(store).updateStatus(2, TopicStatus.ACTIVE, TopicStatus.DISABLED, NOW, NOW);
            o.verify(pins).terminateForSlot("TOPIC:2", NOW);
            assertThat(auditSummary(AuditActions.TOPIC_DISABLED)).contains("-> DISABLED").contains("pinsEnded=2")
                    .doesNotContain("desc id");
        }

        @Test
        void disableHiddenTopic() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.HIDDEN, null)));
            service.disable(ADMIN, 2);
            verify(store).updateStatus(2, TopicStatus.HIDDEN, TopicStatus.DISABLED, NOW, NOW);
            verify(pins).terminateForSlot("TOPIC:2", NOW);
        }

        @Test
        void restoreDisabledClearsDisabledAtAndDoesNotRefeature() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.DISABLED, null)));
            service.restore(ADMIN, 2);
            verify(store).updateStatus(eq(2L), eq(TopicStatus.DISABLED), eq(TopicStatus.ACTIVE), isNull(), eq(NOW));
            verify(store, never()).setFeaturedOrder(anyLong(), any());
            verifyNoInteractions(pins);
            assertThat(auditSummary(AuditActions.TOPIC_RESTORED)).contains("DISABLED -> ACTIVE");
        }

        @Test
        void restoreActiveIsStale() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, null)));
            assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.restore(ADMIN, 2)), "admin.err.topics.stale");
        }

        @Test
        void concurrentRestoreLosesTheStatusGuardAndIsStaleWithoutAudit() {
            // 两个运营同时点「恢复」：第二个的 UPDATE … AND status = 'DISABLED' 命中 0 行
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.DISABLED, null)));
            when(store.updateStatus(anyLong(), any(), any(), any(), any())).thenReturn(0);
            assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.restore(ADMIN, 2)), "admin.err.topics.stale");
            verifyNoInteractions(audit);
        }

        @Test
        void concurrentDisableLosesTheStatusGuardAndEndsNoPins() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.updateStatus(anyLong(), any(), any(), any(), any())).thenReturn(0);
            assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.disable(ADMIN, 2)), "admin.err.topics.stale");
            verifyNoInteractions(pins);
            verifyNoInteractions(audit);
        }

        @Test
        void concurrentHideLosesTheStatusGuard() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, null)));
            when(store.updateStatus(anyLong(), any(), any(), any(), any())).thenReturn(0);
            assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> service.hide(ADMIN, 2)), "admin.err.topics.stale");
            verifyNoInteractions(audit);
        }

        @Test
        void eachWriteAuditsExactlyOnce() {
            when(store.findById(2)).thenReturn(Optional.of(topic(2, "a2", TopicType.USER, TopicStatus.ACTIVE, null)));
            service.hide(ADMIN, 2);
            verify(audit, times(1)).record(any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void missingTopicIs404Code() {
            when(store.findById(99)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.restore(ADMIN, 99))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.topics.notFound");
        }
    }

    @Test
    void filterIgnoresLeadingHashAndNormalizes() {
        assertThat(TopicFilter.normalizeQuery(" ##KucingLucu ")).isEqualTo("kucinglucu");
        assertThat(TopicFilter.normalizeQuery("#")).isNull();
        assertThat(TopicFilter.of("BOGUS", "HIDDEN", null)).isEqualTo(new TopicFilter(null, TopicStatus.HIDDEN, null));
        assertThat(AdminTopicStore.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }
}
