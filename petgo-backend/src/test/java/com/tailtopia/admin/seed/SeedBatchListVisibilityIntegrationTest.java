package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.service.SeedBatchEntryService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.content.domain.ContentType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：批次列表只展示**已保存过**的批次（bug 20260826）。
 *
 * <h2>修复前的实机表现</h2>
 * 运营点「新建批次」的那一刻批次就已落库并出现在列表里 —— 哪怕进去什么都没填、
 * 直接退出，列表里也留下一条 0 行的空批次，而且<b>没有删除入口</b>。
 * 运营的心智是「填完点保存才算数」，与实现完全相反，列表很快被点错的空批次占满。
 *
 * <h2>为什么不是「点击时先不建批次」</h2>
 * 工作台上的每一个子表单（批次设置 / 粘贴 / 加行 / Excel 导入 / 素材墙）都按
 * {@code /admin/seed-batches/{batchId}/…} 提交 —— 没有 id 一个都发不出去。
 * 真要延后创建，得把整页改成「先在前端攒一份草稿再整体提交」，
 * 那是重做而不是修 bug。所以批次照旧在点击时创建，**只是列表把它藏起来**，
 * 直到第一次真正保存。工作台上有一条横幅如实说明这件事。
 */
class SeedBatchListVisibilityIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService batches;

    @Autowired
    private SeedBatchEntryService entry;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication superAdmin() {
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "batchlist-" + SEQ.incrementAndGet() + "@tailtopia.test", "批次列表超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    /// 🔴 **上传与看情况必须在同一页**（产品 2026-08-26）。
    ///
    /// 原先「批量内容」管上传、「排期管理」管查看，分在侧栏两个入口 ——
    /// 运营发完一批要换页才知道它们什么时候发、发没发。
    ///
    /// ⚠️ 断言的是**排期段真的渲染在这一页上**，不是「侧栏少了一个入口」：
    /// 只删导航而没把内容搬过来，等于把功能藏了起来，而那种改动看截图是看不出来的。
    @org.junit.jupiter.api.Test
    void batchListPageAlsoShowsTheScheduleSection() throws Exception {
        String html = mvc.perform(get("/admin/seed-batches").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html)
                .as("🔴 排期段没渲染在批次列表页上 —— 运营仍要换页才知道发布情况")
                // 排期段的两个特征：按发布账号筛选的表单（action 指向排期页）+ WIB 明示。
                .contains("/admin/content-schedules")
                .contains("WIB");
        assertThat(html).as("这仍应是批次列表页本身").contains("批次列表");
    }

    private boolean listed(long batchId) {
        return batches.recentBatches().stream().anyMatch(b -> b.batchId() == batchId);
    }

    @Test
    void freshlyOpenedBatchStaysOutOfTheList() {
        SeedBatch b = batches.openBatch(SeedBatch.Source.ONLINE_PASTE, 1L);
        assertThat(listed(b.getId()))
                .as("🔴 只点了「新建批次」、什么都没填就不该占列表 —— 这正是本 bug 的现象")
                .isFalse();
    }

    /** 点「保存批次设置」即算保存过，**哪怕三个默认值全留空**。 */
    @Test
    void savingBatchSettingsPutsItInTheList() {
        SeedBatch b = batches.openBatch(SeedBatch.Source.ONLINE_PASTE, 1L);
        entry.saveDefaults(b.getId(), null, null, null);
        assertThat(listed(b.getId()))
                .as("🔴 运营点了保存却仍不出现，比原来那个 bug 更让人摸不着头脑 —— "
                        + "所以判据是「点没点保存」，不是「有没有填出内容」")
                .isTrue();
    }

    /** 加了行同样算保存过（粘贴 / 手动加行 / Excel 导入三条路都汇到 addDraft）。 */
    @Test
    void addingARowPutsItInTheList() {
        SeedBatch b = batches.openBatch(SeedBatch.Source.EXCEL, 1L);
        batches.addDraft(b.getId(), 1, 1L, ContentType.DAILY, null, "isi", List.of(), List.of());
        assertThat(listed(b.getId()))
                .as("导入了内容却在列表里找不到，等于内容丢了")
                .isTrue();
    }

    /** 首次保存的时刻**不被后续保存刷新** —— 它是「进不进列表」的开关，不是最后修改时间。 */
    @Test
    void savedAtRecordsTheFirstSaveOnly() {
        SeedBatch b = batches.openBatch(SeedBatch.Source.ONLINE_PASTE, 1L);
        entry.saveDefaults(b.getId(), null, ContentType.DAILY, null);
        var first = batches.recentBatches().stream()
                .filter(x -> x.batchId() == b.getId()).findFirst().orElseThrow();
        entry.saveDefaults(b.getId(), null, ContentType.KNOWLEDGE, null);
        assertThat(listed(b.getId())).isTrue();
        assertThat(first).isNotNull();
    }
}
