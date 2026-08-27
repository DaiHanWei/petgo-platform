package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量内容录入（V1.1.6 Story 13.3 · AB-3K Step 0/2）。
 *
 * <p><b>此前是两条并列路径</b>：「多行纯文本 {@code 文本 ||| 图URL1, 图URL2}」与「Excel 导入」——
 * 共用同一个后端（Excel 只是把前两列拼回同一个竖线字符串），且**各自带一个一模一样的账号下拉，
 * 所以下拉在同一页面出现两次**。
 *
 * <p>本类把两条路合到同一套字段规则上：字段语义由 {@link SeedRowDefaults} 一处定义，
 * 🛡 <b>各自实现一遍迟早分叉</b>，而分叉的表现是"同一份内容用两种方式录进来结果不同"。
 *
 * <h2>🔴 粘贴分行只按单行拆</h2>
 * 界面**必须明示**这一点（AC3）。运营若粘入一篇带段落的长科普，会被拆成一堆残句 ——
 * <b>而且不会有任何报错</b>（每一段都是合法的正文），只能手动合并回去，比原来的竖线格式更糟。
 *
 * <p>⚠️ 刻意**不选**"按空行分隔"：它的失败模式更隐蔽 ——
 * 运营从表格复制的短文案（本来就没有空行）会被<b>静默合并成一条</b>。
 */
@Service
public class SeedBatchEntryService {

    private final SeedBatchRepository batches;
    private final SeedBatchRowRepository rows;
    private final SeedBatchAssetRepository assets;
    private final UserRepository users;
    private final AccountSpeciesDefaultReader accountSpecies;

    public SeedBatchEntryService(SeedBatchRepository batches, SeedBatchRowRepository rows,
            SeedBatchAssetRepository assets, UserRepository users,
            AccountSpeciesDefaultReader accountSpecies) {
        this.batches = batches;
        this.rows = rows;
        this.assets = assets;
        this.users = users;
        this.accountSpecies = accountSpecies;
    }

    /** 一行录入的原始输入（两条路径都归一到这个形状）。 */
    public record RawRow(String body, List<String> assetFileNames, Long authorUserId,
            ContentType contentType, String species, Instant scheduledAt) {

        public static RawRow ofBody(String body) {
            return new RawRow(body, List.of(), null, null, null, null);
        }
    }

    // ——————————————————— 批次设置 ———————————————————

    /**
     * 页头那一处批次级设置（AC1）。
     *
     * <p>🔴 内容类型**拒绝 {@code GROWTH_MOMENT}**（A-10）：批量不支持成长日历 ——
     * 它需逐行绑定具体宠物与事件日期、且属"真实记录"性质，不适合批量灌入。
     * 服务端必须自己拦一遍：下拉里没有它只是界面，改个请求参数就能绕过。
     */
    @Transactional
    public void saveDefaults(long batchId, Long authorUserId, ContentType type, Instant scheduledAt) {
        if (type == ContentType.GROWTH_MOMENT) {
            throw AppException.validation("批量发布不支持「成长日历」类型，请用单条发布");
        }
        SeedBatch b = requireBatch(batchId);
        b.applyDefaults(authorUserId, type, scheduledAt);
        // 点了「保存批次设置」即视为保存过 —— 哪怕三个默认值都留空。
        // ⚠️ 不能靠「有没有填出内容」来推断：运营点了保存却仍不出现在列表里，
        //    比原来那个 bug 更让人摸不着头脑。
        b.markSaved();
        batches.save(b);
    }

    // ——————————————————— 在线录入 ———————————————————

    /**
     * 粘贴多行文本 → 逐行生成草稿（AC2/AC3）。
     *
     * <p>**按单行分隔，一行一条**；🛡 空行跳过、<b>不生成空内容行</b> ——
     * 生成一堆空行的话运营还得逐个删，比不拆更麻烦。
     *
     * @return 新生成的行数
     */
    @Transactional
    public int pasteLines(long batchId, String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("粘贴内容为空");
        }
        List<RawRow> parsed = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                parsed.add(RawRow.ofBody(t));
            }
        }
        if (parsed.isEmpty()) {
            throw AppException.validation("粘贴内容里没有有效的行");
        }
        return appendRows(batchId, parsed).size();
    }

    /** 手动加一空行（运营想直接在页面上逐行编辑时用）。 */
    @Transactional
    public SeedBatchRow addBlankRow(long batchId) {
        return appendRows(batchId, List.of(RawRow.ofBody(""))).get(0);
    }

    /**
     * 把归一后的原始行落成草稿。
     *
     * <p>行号接着当前最大行号往后排 —— 🛡 <b>分次粘贴时不能从 1 重新开始</b>：
     * 行号是运营对照自己那份表格的坐标，重号会让"第 7 行错了"指向两处。
     */
    @Transactional
    public List<SeedBatchRow> appendRows(long batchId, List<RawRow> raws) {
        SeedBatch batch = requireBatch(batchId);
        int nextNo = rows.findByBatchIdOrderByRowNoAsc(batchId).stream()
                .mapToInt(SeedBatchRow::getRowNo).max().orElse(0) + 1;
        Map<String, SeedBatchAsset> byName = assetsByName(batchId);

        List<SeedBatchRow> saved = new ArrayList<>(raws.size());
        for (RawRow raw : raws) {
            Long authorId = SeedRowDefaults.authorUserId(raw.authorUserId(), batch).orElse(null);
            ContentType type = SeedRowDefaults.contentType(raw.contentType(), batch).orElse(null);
            Instant at = SeedRowDefaults.scheduledAt(raw.scheduledAt(), batch);
            User author = authorId == null ? null : users.findById(authorId).orElse(null);
            String species = SeedRowDefaults.species(raw.species(), author, accountSpecies);

            // 🛡 缺账号 / 缺类型**不阻止入库** —— 它们是**校验错误**，属 13-4 的预览要展示的东西。
            //    在这里抛错会把整批粘贴一起挡掉，而运营的本意只是先把文案贴进来。
            List<String> problems = new ArrayList<>();
            if (authorId == null) {
                problems.add("未指定发布账号，且批次未设默认");
            }
            if (type == null) {
                problems.add("未指定内容类型，且批次未设默认");
            }
            List<String> urls = new ArrayList<>();
            for (String name : raw.assetFileNames()) {
                SeedBatchAsset a = byName.get(name);
                if (a == null) {
                    // ⚠️ 报的是**文件名**而不是内部 id：运营认的是文件名。
                    problems.add("素材「" + name + "」不在本批素材里");
                } else {
                    urls.add(a.getUrl());
                }
            }

            SeedBatchRow row = SeedBatchRow.draft(batchId, nextNo++,
                    // 账号缺失时先落 0 占位：状态是 DRAFT + 有错误信息，13-4 会拦住它不让发。
                    authorId == null ? 0L : authorId,
                    type == null ? ContentType.DAILY : type,
                    null, blankToNull(raw.body()), urls.isEmpty() ? null : urls, null, species);
            if (at != null) {
                row.setScheduledAt(at);
            }
            if (!problems.isEmpty()) {
                row.setErrorMessage(String.join("；", problems));
            }
            saved.add(rows.save(row));
        }
        return saved;
    }

    /** 逐行编辑（行卡片上的保存）。空值一律表示"继承默认"，与录入时同一套规则。 */
    @Transactional
    public void editRow(long rowId, String body, List<String> assetFileNames, Long authorUserId,
            ContentType contentType, String species, Instant scheduledAt) {
        SeedBatchRow row = rows.findById(rowId)
                .orElseThrow(() -> AppException.notFound("内容行不存在"));
        SeedBatch batch = requireBatch(row.getBatchId());
        Long resolvedAuthor = SeedRowDefaults.authorUserId(authorUserId, batch).orElse(null);
        ContentType resolvedType = SeedRowDefaults.contentType(contentType, batch).orElse(null);
        if (resolvedType == ContentType.GROWTH_MOMENT) {
            throw AppException.validation("批量发布不支持「成长日历」类型");
        }
        Map<String, SeedBatchAsset> byName = assetsByName(row.getBatchId());
        List<String> urls = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : assetFileNames) {
            SeedBatchAsset a = byName.get(name);
            if (a == null) {
                missing.add(name);
            } else {
                urls.add(a.getUrl());
            }
        }
        User author = resolvedAuthor == null ? null : users.findById(resolvedAuthor).orElse(null);
        row.edit(resolvedType == null ? row.getContentType() : resolvedType, null,
                blankToNull(body), urls.isEmpty() ? null : urls, null);
        if (resolvedAuthor != null) {
            row.setAuthorUserId(resolvedAuthor);
        }
        row.setSpecies(SeedRowDefaults.species(species, author, accountSpecies));
        row.setScheduledAt(SeedRowDefaults.scheduledAt(scheduledAt, batch));
        row.setErrorMessage(missing.isEmpty() ? null
                : "素材不在本批素材里：" + String.join("、", missing));
        rows.save(row);
    }

    @Transactional
    public void deleteRow(long rowId) {
        rows.deleteById(rowId);
    }

    // ——————————————————— 内部 ———————————————————

    private Map<String, SeedBatchAsset> assetsByName(long batchId) {
        Map<String, SeedBatchAsset> byName = new LinkedHashMap<>();
        for (SeedBatchAsset a : assets.findByBatchIdAndOrphanedAtIsNull(batchId)) {
            byName.put(a.getFileName(), a);
        }
        return byName;
    }

    private SeedBatch requireBatch(long batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> AppException.notFound("批次不存在"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 供 Excel 导入复用：批次实体（读）。 */
    @Transactional(readOnly = true)
    public Optional<SeedBatch> findBatch(long batchId) {
        return batches.findById(batchId);
    }
}
