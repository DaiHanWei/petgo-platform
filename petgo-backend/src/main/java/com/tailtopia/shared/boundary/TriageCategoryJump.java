package com.tailtopia.shared.boundary;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.shop.domain.ProductCategory;

/**
 * 🔴 <b>问诊结论页唯一允许的商品关联</b>（Story 9.1，FR-110）。
 *
 * <p>规则一句话：<b>按结论中的结构化健康记录类型跳转对应品类，且由系统生成。</b>
 *
 * <p>🔴 <b>兽医无法选择具体 SKU —— 不是"有入口但被权限拦住"，是根本没有那个入口</b>：
 * 本类的输入是 {@link HealthRecordType}（一个枚举），输出是 {@link ProductCategory}（另一个枚举）。
 * <b>整条路径上不存在任何可以携带 SKU 的地方</b>，这就是"能力缺席"的实现形态。
 *
 * <p>⚠️ 埋点 {@code triage_to_category_jump}（含 {@code record_type}）用于监控<b>边界侵蚀</b>：
 * 问诊 → 商品的跳转占比若持续走高，说明问诊正在被当作销售前端使用 —— 那是要立刻叫停的信号，
 * 不是要庆祝的转化。
 */
public final class TriageCategoryJump {

    private TriageCategoryJump() {
    }

    /**
     * 结构化健康记录类型 → 商品品类。
     *
     * <p>🔴 <b>只映射两类</b>（驱虫 / 疫苗后的护理用药），其余一律返回 {@code null} = <b>不跳</b>。
     * 把「月经 / 绝育 / 自定义」也映射过去，就等于给每一条问诊结论都配一个购物入口 ——
     * 那正是 FR-110 要防的形态。
     */
    public static ProductCategory categoryFor(HealthRecordType recordType) {
        if (recordType == null) {
            return null;
        }
        return switch (recordType) {
            case DEWORM, VACCINE -> ProductCategory.OBAT_VITAMIN;
            // 🔴 其余类型不跳。默认分支写成 null 而不是某个"兜底品类"是刻意的。
            default -> null;
        };
    }

    /** 该结论是否允许出现品类跳转入口。 */
    public static boolean allowsJump(HealthRecordType recordType) {
        return categoryFor(recordType) != null;
    }
}
