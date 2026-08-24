package com.tailtopia.content.larksync;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lark 定时发帖编排（spec-lark-scheduled-posts）：每小时从《List of Content for Automatic Upload》
 * 取「上传状态」为空的最靠前一行 → 云盘图片转存 OSS → 随机虚拟账号经
 * {@link ContentService#publishTrusted} 发布（免审，运营=可信主体）→ 回写表格 E/F 列。
 *
 * <p><b>每轮最多成功发布一条</b>（控制放出节奏）；<b>失败分两级</b>：
 * <ul>
 *   <li><b>轮级</b>（{@link LarkContentClient.LarkApiException}：token/读表/云盘/下载等传输
 *       与平台层失败，及作者池穷尽这类系统性配置错）——立即中止本轮、只记日志、
 *       <b>不写任何脏状态</b>，下小时自动重试；</li>
 *   <li><b>行级</b>（缺图/编号非法/文案超长等内容性失败）——记 FAILED + 回写「失败：原因」
 *       后顺延下一行；连续 {@value #MAX_ROW_FAILURES} 行失败触发熔断中止本轮，
 *       防止系统性问题把整表涂成失败。</li>
 * </ul>
 *
 * <p>幂等防线：① 发帖与 {@code lark_content_publishes} 落库绑在<b>同一事务</b>
 * （杜绝「发成未记账」崩溃窗口——Redis 幂等键仅 24h TTL，兜不住跨日重试）；
 * ② {@code content_code} DB 唯一（跨轮去重）；③ 表格「上传状态」非空即跳过（运营可见位）。
 * 回写前按内容编号<b>重定位行号</b>——开轮快照与回写之间运营插/删行不会标错行。
 * 同表出现重复编号时只认首个出现的行，其余行标「编号重复」且绝不碰 DB。
 * 无锁——单实例部署 + 全库 @Scheduled 同范式。
 */
@Service
public class LarkContentSyncService {

    private static final Logger log = LoggerFactory.getLogger(LarkContentSyncService.class);
    private static final DateTimeFormatter WIB_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    /** 内容/图片编号白名单（拼进 OSS objectKey 与公网 URL，脏字符一律拒）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** 行级失败熔断阈值：连续失败到这个数就中止本轮（多半是系统性问题，别再涂表）。 */
    static final int MAX_ROW_FAILURES = 5;

    private final LarkContentSyncProperties props;
    private final LarkContentClient client;
    private final LarkContentPublishRepository records;
    private final ContentService contentService;
    private final AliyunOssClient oss;
    private final MediaProperties mediaProps;
    private final UserRepository users;
    private final TransactionTemplate tx;

    public LarkContentSyncService(LarkContentSyncProperties props, LarkContentClient client,
            LarkContentPublishRepository records, ContentService contentService,
            AliyunOssClient oss, MediaProperties mediaProps, UserRepository users,
            PlatformTransactionManager txManager) {
        this.props = props;
        this.client = client;
        this.records = records;
        this.contentService = contentService;
        this.oss = oss;
        this.mediaProps = mediaProps;
        this.users = users;
        this.tx = new TransactionTemplate(txManager);
        if (!props.isLive() && !"off".equalsIgnoreCase(props.getMode())) {
            log.warn("petgo.lark-content.mode 值无效（{}），按 off 处理——如需启用请配 live", props.getMode());
        }
    }

    /** 每小时整点后 7 分（UTC 基准，cron 可经 env 覆盖）。 */
    @Scheduled(cron = "${petgo.lark-content.sync-cron:0 7 * * * *}", zone = "UTC")
    public void syncOnce() {
        if (!props.isLive()) {
            return; // mode=off（默认）：零外部调用零噪音。
        }
        try {
            runRound();
        } catch (RuntimeException e) {
            // 轮级失败（token/读表/云盘/系统性配置错）：只记日志等下小时，不写任何脏状态。
            log.warn("Lark 发帖本轮失败 cause={} msg={}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void runRound() {
        // 列位置按表头动态定位（运营会改表结构）；必需列缺失 → LarkApiException 轮级中止。
        LarkRowParser.Columns cols = LarkRowParser.mapColumns(client.readHeader());
        List<List<String>> raw = client.readRows();
        List<LarkRowParser.Row> all = LarkRowParser.parse(raw, cols);
        if (all.size() >= props.getRowLimit()) {
            log.warn("Lark 表格数据行数已触及读取上限 rowLimit={}，超出部分不会被处理——请调大 LARK_CONTENT_ROW_LIMIT",
                    props.getRowLimit());
        }
        // 同表重复编号：只认首个出现的行，其余行标重复（防止把别行内容误标已发布/误发）。
        Map<String, Integer> firstRowOf = new HashMap<>();
        for (LarkRowParser.Row r : all) {
            firstRowOf.putIfAbsent(r.contentCode(), r.sheetRowNumber());
        }
        List<LarkRowParser.Row> pending = all.stream().filter(LarkRowParser.Row::pending).toList();
        if (pending.isEmpty()) {
            return;
        }
        // 作者在轮初选定：池穷尽属系统性配置错 → 直接抛（轮级），绝不把行涂成失败。
        long authorId = pickAuthor();

        Map<String, String> folder = null; // 懒加载：全是补回写/重复行时不必列文件夹。
        int rowFailures = 0;
        for (LarkRowParser.Row row : pending) {
            int firstRow = firstRowOf.get(row.contentCode());
            if (firstRow != row.sheetRowNumber()) {
                // 重复编号行：只回写提醒（用快照行号——按编号定位会指到首行），绝不碰 DB。
                writeStatusQuietly(cols, row.sheetRowNumber(),
                        "失败：内容编号与第" + firstRow + "行重复", "");
                continue;
            }
            Optional<LarkContentPublish> existing = records.findByContentCode(row.contentCode());
            if (existing.isPresent()
                    && existing.get().getStatus() == LarkContentPublish.Status.PUBLISHED) {
                // 崩溃窗口修复：已发成但表格没写上——只补回写，不占本轮发帖额度。
                writeBackPublished(cols, row.contentCode(), existing.get().getAuthorId());
                continue;
            }
            // 内容性前置校验：在下载/传 OSS 之前拦住，不给公开桶留孤儿对象。
            String invalid = validateRow(row);
            if (invalid != null) {
                rowFailures++;
                markRowFailed(cols, row, existing, invalid);
                if (rowFailures >= MAX_ROW_FAILURES) {
                    log.warn("Lark 发帖连续 {} 行失败，熔断中止本轮", rowFailures);
                    return;
                }
                continue;
            }
            try {
                if (folder == null) {
                    folder = client.listFolderFiles();
                }
                long postId = publishRow(cols, row, folder, authorId, existing.orElse(null));
                log.info("Lark 发帖成功 code={} postId={}", row.contentCode(), postId);
                return; // 每轮恰好一条，发成即收工。
            } catch (LarkContentClient.LarkApiException e) {
                throw e; // 传输/平台层失败：轮级——不标行、不回写，交 syncOnce 记日志。
            } catch (RuntimeException e) {
                rowFailures++;
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Lark 发帖行失败顺延 code={} reason={}", row.contentCode(), reason);
                markRowFailed(cols, row, existing, reason);
                if (rowFailures >= MAX_ROW_FAILURES) {
                    log.warn("Lark 发帖连续 {} 行失败，熔断中止本轮", rowFailures);
                    return;
                }
            }
        }
    }

    /** 发布一行：下图 → OSS →（事务内）免审发布 + DB 状态机 → 回写。 */
    private long publishRow(LarkRowParser.Columns cols, LarkRowParser.Row row,
            Map<String, String> folder, long authorId, LarkContentPublish existing) {
        List<String> imageUrls = new ArrayList<>();
        for (String code : row.imageCodes()) {
            String fileName = code + ".jpg";
            String fileToken = folder.get(fileName);
            if (fileToken == null) {
                throw new IllegalStateException("缺图 " + fileName);
            }
            byte[] bytes = client.downloadFile(fileToken); // 传输失败/非图片 → LarkApiException（轮级）
            String key = mediaProps.getOss().normalizedKeyPrefix()
                    + "public/lark-content/" + row.contentCode() + "/" + fileName;
            imageUrls.add(oss.putPublicObjectWithAcl(key, bytes, "image/jpeg"));
        }

        ContentPostCreateRequest req = new ContentPostCreateRequest(
                ContentType.DAILY, null, blankToNull(row.text()), imageUrls);
        // 发帖与状态机落库同一事务：要么都成、要么都回滚，杜绝「发成未记账」。
        Long postId = tx.execute(status -> {
            ContentPostResponse post = contentService.publishTrusted(
                    authorId, req, "lark-content:" + row.contentCode());
            LarkContentPublish record = existing;
            if (record != null) {
                record.markPublished(authorId, post.id());
            } else {
                record = LarkContentPublish.published(
                        row.contentCode(), String.join(",", row.imageCodes()), authorId, post.id());
            }
            records.save(record);
            return post.id();
        });
        // 事务已提交 = 发布已成：此后只剩回写，失败仅警告（下轮补），绝不当行失败顺延（防同轮双发）。
        writeBackPublished(cols, row.contentCode(), authorId);
        return postId;
    }

    /** 内容性校验（下载前拦截）。返回失败原因，合法返回 null。 */
    private static String validateRow(LarkRowParser.Row row) {
        if (!CODE_PATTERN.matcher(row.contentCode()).matches()) {
            return "内容编号含非法字符或超长";
        }
        for (String code : row.imageCodes()) {
            if (!CODE_PATTERN.matcher(code).matches()) {
                return "图片编号含非法字符或超长：" + brief(code);
            }
        }
        if (row.imageCodes().size() > 9) {
            return "图片超过 9 张";
        }
        if (String.join(",", row.imageCodes()).length() > 255) {
            return "图片编号列表过长";
        }
        String text = row.text();
        if (text != null && text.length() > 1000) {
            return "文案超过 1000 字";
        }
        if ((text == null || text.isBlank()) && row.imageCodes().isEmpty()) {
            return "文案与图片均为空";
        }
        return null;
    }

    /** 作者池随机取一，跳过不存在/非 ACTIVE 的。池穷尽=系统性配置错 → 抛给轮级。 */
    private long pickAuthor() {
        List<Long> pool = new ArrayList<>(props.getAuthorIds());
        while (!pool.isEmpty()) {
            Long id = pool.remove(ThreadLocalRandom.current().nextInt(pool.size()));
            Optional<User> u = users.findById(id);
            if (u.isPresent() && u.get().getStatus() == UserStatus.ACTIVE) {
                return id;
            }
        }
        throw new IllegalStateException("虚拟作者池无可用账号");
    }

    /** 行级失败：DB 记 FAILED（绝不降级已 PUBLISHED 的记录）+ 回写「失败：原因」。 */
    private void markRowFailed(LarkRowParser.Columns cols, LarkRowParser.Row row,
            Optional<LarkContentPublish> existing, String reason) {
        try {
            if (existing.isPresent()) {
                if (existing.get().getStatus() != LarkContentPublish.Status.PUBLISHED) {
                    existing.get().markFailed(brief(reason));
                    records.save(existing.get());
                }
            } else {
                records.save(LarkContentPublish.failed(row.contentCode(),
                        brief(String.join(",", row.imageCodes())), 0L, brief(reason)));
            }
        } catch (RuntimeException e) {
            log.warn("Lark 失败记录落库失败 code={} cause={}", row.contentCode(),
                    e.getClass().getSimpleName());
        }
        writeBackByCode(cols, row.contentCode(), "失败：" + brief(reason), "");
    }

    /** 回写「已发布 <WIB 时间>」+ 作者昵称。发布已成，任何失败只警告（下轮补）。 */
    private void writeBackPublished(LarkRowParser.Columns cols, String contentCode, long authorId) {
        try {
            String nickname = users.findById(authorId).map(User::getNickname).orElse("");
            String status = "已发布 " + ZonedDateTime.now(WIB).format(WIB_FMT) + " WIB";
            writeBackByCode(cols, contentCode, status, nickname != null ? nickname : "");
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 code={} cause={}", contentCode, e.getClass().getSimpleName());
        }
    }

    /**
     * 按内容编号重定位后回写（防运营中途插/删行标错行）。定位不到（行已被删）则跳过——
     * 宁可漏写等下轮，也不按过期快照写错行。
     */
    private void writeBackByCode(LarkRowParser.Columns cols, String contentCode,
            String status, String account) {
        try {
            Optional<Integer> located = client.findRowByCode(
                    LarkRowParser.Columns.letter(cols.code()), contentCode);
            if (located.isEmpty()) {
                log.warn("Lark 回写跳过：表中已找不到编号 {}", contentCode);
                return;
            }
            client.writeCell(LarkRowParser.Columns.letter(cols.status()), located.get(), status);
            client.writeCell(LarkRowParser.Columns.letter(cols.account()), located.get(), account);
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 code={} cause={}", contentCode, e.getClass().getSimpleName());
        }
    }

    /** 直接按行号静默回写（仅重复编号行使用——按编号定位会指到首行）。 */
    private void writeStatusQuietly(LarkRowParser.Columns cols, int sheetRowNumber,
            String status, String account) {
        try {
            client.writeCell(LarkRowParser.Columns.letter(cols.status()), sheetRowNumber, status);
            client.writeCell(LarkRowParser.Columns.letter(cols.account()), sheetRowNumber, account);
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 row={} cause={}", sheetRowNumber, e.getClass().getSimpleName());
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
