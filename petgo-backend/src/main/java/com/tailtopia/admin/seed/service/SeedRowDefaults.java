package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.Optional;

/**
 * 四个字段的继承规则（V1.1.6 Story 13.3 · AC5）。
 *
 * <p><b>运营心智对四个字段是一致的</b>：都是「填了就用你填的，留空就继承默认」。
 * 差别只在**默认值来自哪里**：
 *
 * <table>
 *   <tr><th>字段</th><th>留空时取什么</th></tr>
 *   <tr><td>发布账号</td><td>批次默认</td></tr>
 *   <tr><td>内容类型</td><td>批次默认</td></tr>
 *   <tr><td>计划发布时间</td><td>批次默认（批次默认也空 = 立即发布）</td></tr>
 *   <tr><td><b>关联物种</b></td>
 *       <td>🔴 <b>无批次默认（刻意）</b>：虚拟账号 → 继承其「账号物种定位」；
 *           运营真实账号 → 留空入库，由算法按作者宠物档案推导</td></tr>
 * </table>
 *
 * <p>🔴 <b>「关联物种」为什么刻意不设批次默认</b>（A-14）：账号物种定位本身就在扮演
 * 账号级默认值的角色、而且扮演得更好 —— 配一次永久生效、跟着账号走，不必每批重设。
 * 再加一层批次默认会与它冲突：批次默认设「猫」、行留空、而该行发布账号是狗号时，
 * <b>取谁没有正确答案</b>。
 *
 * <p>🛡 <b>两条录入路径（在线 / Excel）共用本类</b> —— 字段语义必须完全一致，
 * 各自实现一遍迟早分叉，而分叉的表现是"同一份内容用两种方式录进来结果不同"。
 */
public final class SeedRowDefaults {

    private SeedRowDefaults() {
    }

    /**
     * 解析发布账号。
     *
     * <p>⚠️ 行填了就用行的；都没有则返回空 —— 调用方据此报"请先设置默认发布账号"。
     * 🔴 <b>这覆盖了 V1.1.0 原「留空 = 校验失败」的规则</b>：现在留空是**正常用法**。
     */
    public static Optional<Long> authorUserId(Long rowValue, SeedBatch batch) {
        if (rowValue != null) {
            return Optional.of(rowValue);
        }
        return Optional.ofNullable(batch.getDefaultAuthorUserId());
    }

    /** 解析内容类型。留空继承批次默认；两者都空则由调用方报错。 */
    public static Optional<ContentType> contentType(ContentType rowValue, SeedBatch batch) {
        if (rowValue != null) {
            return Optional.of(rowValue);
        }
        return Optional.ofNullable(batch.getDefaultContentType());
    }

    /**
     * 解析计划发布时间。
     *
     * <p>⚠️ <b>两者都空是合法的，意思是"立即发布"</b> —— 所以这里返回 {@code null}
     * 而不是抛错。把"没排期"当成错误会让最常用的那条路径（马上发）变得要多点一次。
     */
    public static Instant scheduledAt(Instant rowValue, SeedBatch batch) {
        return rowValue != null ? rowValue : batch.getDefaultScheduledAt();
    }

    /**
     * 解析关联物种。
     *
     * <p>🔴 <b>没有批次默认这一层</b>（见类注释）。留空时：
     * <ul>
     *   <li>虚拟账号 → 继承其「账号物种定位」（由 {@link AccountSpeciesDefaultReader} 读，
     *       该字段本体属 Story 14-1）</li>
     *   <li>运营真实账号 → <b>留空入库</b>，交给算法按作者的宠物档案推导 ——
     *       它有真实档案，猜不如让算法读</li>
     * </ul>
     */
    public static String species(String rowValue, User author,
            AccountSpeciesDefaultReader accountSpecies) {
        if (rowValue != null && !rowValue.isBlank()) {
            return rowValue.trim();
        }
        if (author != null && author.getAccountType() == AccountType.VIRTUAL) {
            return accountSpecies.speciesOf(author.getId()).orElse(null);
        }
        return null;
    }
}
