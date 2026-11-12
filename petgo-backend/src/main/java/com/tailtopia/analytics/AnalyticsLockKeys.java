package com.tailtopia.analytics;

/**
 * 埋点管道 advisory lock 锁键登记处（analytics-pipeline Story 1.1）。<b>后续任务的锁键只从这里取</b>，不准就地写字面量。
 *
 * <p>一律两段式：{@code SELECT pg_try_advisory_xact_lock(NAMESPACE, <KEY>)}（事务级，事务结束自动释放）。
 * {@link #NAMESPACE} 固定为 {@code 0x414E4C59}（ASCII "ANLY"），与业务库里的单段 {@code hashtext(...)} 锁、
 * 其他命名空间的两段式锁（如 AI 查数）都分开。
 *
 * <ul>
 *   <li>🔴 锁<b>只在埋点库上拿</b>（用 {@code @Qualifier("analytics")} 的 {@code JdbcTemplate} / {@code TransactionTemplate}），
 *       不在业务库上拿。</li>
 *   <li>PG advisory lock 按<b>数据库</b>隔离：生产库 {@code petgo_analytics} 与 stag 库 {@code petgo_analytics_stag}
 *       在同一实例上也不会互相挡。</li>
 *   <li>新增任务：在下面追加一个常量，值不得与已有的重复（{@code AnalyticsLockKeysTest} 断言）。</li>
 * </ul>
 */
public final class AnalyticsLockKeys {

    /** 两段式锁的命名空间："ANLY"。 */
    public static final int NAMESPACE = 0x414E4C59;

    /** Story 1.3：原始层 / 明细层分区维护。 */
    public static final int PARTITION_MAINTENANCE = 1;
    /** Story 1.4：未登记事件每日告警。 */
    public static final int UNKNOWN_EVENT_ALERT = 2;
    /** Story 1.6：明细层转换（DetailBuilder）。 */
    public static final int DETAIL_BUILDER = 3;
    /** Story 1.8：注销擦除。 */
    public static final int ERASURE = 4;
    /** Story 1.10：存储水位检查与停写熔断。 */
    public static final int STORAGE_WATERMARK = 5;
    /** Epic 4：指标汇总。 */
    public static final int ROLLUP = 6;
    /** Epic 4：GA4 快照与日活对账。 */
    public static final int GA4_SNAPSHOT = 7;

    private AnalyticsLockKeys() {
    }
}
