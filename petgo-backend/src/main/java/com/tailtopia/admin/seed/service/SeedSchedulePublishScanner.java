package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到点自动发布（V1.1.6 Story 13.5 · AB-3L · AC2/AC5）。
 *
 * <h2>为什么要有定时发布</h2>
 * ① 人不在手机边上时要能预排；
 * ② 批量一次灌入几十条，Feed 上时间戳**全挤在同一分钟**，观感明显不真实 ——
 * 按节奏铺开本身就是内容运营需求。
 *
 * <h2>🛡 AC2：走与即时发布**完全相同的链路**，含内容自动审核、无豁免</h2>
 * 本类<b>不自己拼发布请求</b>，而是调 {@link SeedBatchPublishService#publishRowNow} ——
 * 与运营点"确认发布"走的是同一个方法。
 * 🔴 「另写一条到点发布的快路、顺便跳过审核」是最容易被想到的省事做法，
 * 也是这条 AC 明令禁止的（延续 V1.0.0「种子内容不设审核豁免」）。
 * 共用同一个方法是让那条 AC 在**结构上**成立，而不是靠两处代码碰巧一致。
 *
 * <h2>🔴 AC6：到点**不再做去重校验**</h2>
 * 这是刻意的取舍（A-16）：「到点必发」这一确定性比「避免重复内容」更重要 ——
 * 重复内容运营发现后可下架，而<b>"排好的内容莫名没发"会让运营对定时功能失去信任、
 * 退回手动发布</b>。
 * 代价是：排期期间若出现近似内容（**含账号持有人自己在 App 内发布的**），到点仍照发。
 *
 * <h2>🛡 AC5：失败不自动重试</h2>
 * 标记 {@code FAILED} 并注明原因，留在排期列表里供运营处理。
 * 自动重试会在"内容审核硬拦截"这类**必然再失败**的情形上空转，
 * 而运营看到的仍是一条"还在排队"的行。
 */
@Component
public class SeedSchedulePublishScanner {

    private static final Logger log = LoggerFactory.getLogger(SeedSchedulePublishScanner.class);

    /**
     * 一轮最多发多少条。
     *
     * <p>⚠️ 有上限是因为一批可能有 200 行、且都排在同一分钟（运营忘了铺开时间）。
     * 一轮全发完会让这个事务开很久、并把 Feed 瞬间灌满 —— 而下一轮 30 秒后就来了，
     * 分几轮发反而更接近"按节奏铺开"的本意。
     */
    private static final int MAX_PER_SCAN = 50;

    private final SeedBatchRowRepository rows;
    private final com.tailtopia.admin.seed.repository.SeedBatchRepository batches;
    private final SeedBatchPublishService publishing;
    private final ObjectProvider<SeedSchedulePublishScanner> selfProvider;

    public SeedSchedulePublishScanner(SeedBatchRowRepository rows,
            com.tailtopia.admin.seed.repository.SeedBatchRepository batches,
            SeedBatchPublishService publishing,
            ObjectProvider<SeedSchedulePublishScanner> selfProvider) {
        this.rows = rows;
        this.batches = batches;
        this.publishing = publishing;
        this.selfProvider = selfProvider;
    }

    /**
     * 扫一轮。
     *
     * <p>做法照抄后台既有的 8 处扫描器：{@code @Scheduled} + DB 状态守卫，
     * <b>禁 MQ / 延迟队列</b>（架构 enforcement 护栏）。
     * 幂等：状态从 {@code SCHEDULED} 转走之后就不在扫描集里了。
     *
     * <p>⚠️ <b>逐行一个事务</b>（{@code publishRowNow} 与失败标记各自事务）——
     * 一整轮包在一个事务里的话，第 30 条因内容审核拦下抛异常会把前 29 条一起回滚，
     * 而那 29 条已经真的发出去了（内容表写入 + 指纹），回滚只会让状态与事实不一致。
     */
    @Scheduled(fixedDelayString = "${petgo.seed-batch.schedule-scan-ms:30000}")
    public void publishDueRows() {
        List<SeedBatchRow> due = rows
                .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        SeedBatchRowStatus.SCHEDULED, Instant.now());
        if (due.isEmpty()) {
            return;
        }
        int published = 0;
        int failed = 0;
        for (SeedBatchRow row : due.stream().limit(MAX_PER_SCAN).toList()) {
            // 🔴 经 self 代理：this.publishOne() 自调用会绕过代理，@Transactional 形同虚设 ——
            //    publish / 写指纹 / markPublished 变成各自提交，中途失败 = 帖子已上线但行仍 SCHEDULED（下轮重发）。
            if (selfProvider.getObject().publishOne(row.getId())) {
                published++;
            } else {
                failed++;
            }
        }
        log.info("定时发布扫描：到点 {} 条，发出 {} 条，失败 {} 条（失败不自动重试）",
                due.size(), published, failed);
    }

    /**
     * 发一条。返回是否成功。
     *
     * <p>🛡 单独一个事务：一条失败不影响其余（见 {@link #publishDueRows} 的注释）。
     */
    @Transactional
    public boolean publishOne(long rowId) {
        SeedBatchRow row = rows.findById(rowId).orElse(null);
        // 并发守卫：另一轮扫描（或运营手动确认）可能已经把它发了 / 取消了。
        if (row == null || row.getStatus() != SeedBatchRowStatus.SCHEDULED) {
            return false;
        }
        try {
            // 🛡 与"确认发布"同一个方法 —— 含自动审核，无豁免。
            //
            // ⚠️ 记的操作人是**建这个批次的后台账号** —— 也就是当初排下这条期的人。
            //    最初我误把 batchId 传进了 adminAccountId 那个位置（两个都是 long，编译不报），
            //    于是审计里会记下一个不存在的后台账号 id。
            //    🔴 这类"类型对得上、含义完全错"的传参，编译器与测试都不会替你发现。
            long actingAdmin = batches.findById(row.getBatchId())
                    .map(com.tailtopia.admin.seed.domain.SeedBatch::getCreatedBy)
                    .orElse(0L);
            publishing.publishRowNow(row, actingAdmin);
            return true;
        } catch (RuntimeException e) {
            // 到点失败的典型原因：内容审核硬拦截 / 发布账号已移出身份池或已禁用 / 绑定宠物已删除。
            // ⚠️ 捕 RuntimeException 而不是 AppException：对象存储抖动之类会抛别的类型，
            //    而"一条挂了把整轮拖垮"是最糟的结果。
            log.warn("定时发布失败 rowId={} : {}", rowId, e.toString());
            publishing.safelyFail(rowId, reasonOf(e));
            return false;
        }
    }

    /**
     * 失败原因。
     *
     * <p>🛡 <b>要让运营看得懂"我该怎么办"</b>：账号被移出、内容被审核拦下、宠物档案删了，
     * 处置方式完全不同。异常消息为空时给一句兜底而不是留空 ——
     * 一条没有原因的失败等于让运营自己猜。
     */
    private static String reasonOf(RuntimeException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "到点发布失败（原因未记录，请查看服务日志）" : m;
    }

    /** 供排期列表页显示"下一轮大约什么时候扫" —— 运营最常问的就是这个。 */
    public static int scanIntervalSecondsHint() {
        return 30;
    }
}
