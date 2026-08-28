package com.tailtopia.content.larksync;

import com.tailtopia.auth.domain.Role;
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
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lark 定时发帖编排（spec-lark-scheduled-posts）：每小时从《List of Content for Automatic Upload》
 * 取「上传状态」为空的最靠前一行 → 云盘图片转存 OSS → 以指定/随机作者经
 * {@link ContentService#publishTrusted} 发布（免审，运营=可信主体）→ 回写表格「上传状态/发布账号/备注」。
 *
 * <p><b>作者</b>（2026-08-27 起）：「发布账号(邮箱)」列填了邮箱 → 精确匹配该邮箱的 USER 角色、ACTIVE 用户
 * （真实号/虚拟号都可——官方运营号是真实注册的账号，2026-08-28 拍板去掉「仅虚拟号」限制）；
 * 匹配不到 → 该行<b>无效</b>。留空 → 作者池随机虚拟账号（原逻辑）。
 *
 * <p><b>内容分类</b>（2026-08-28 起）：「内容分类」列 {@code Moment}→DAILY、{@code Knowledge}→KNOWLEDGE
 * （不区分大小写）；空 → DAILY；其他值 → 该行<b>无效</b>（备注写明可填值）。表里没这列 → 全部 DAILY。
 *
 * <p><b>图片</b>（2026-08-27 起）：「图片编号」列填<b>前缀</b>（如 {@code DR260823001}，不得含 {@code -}），
 * 云盘按 {@code {前缀}-{整数}.jpg} 匹配全部文件、按整数升序上传；{@code -} 后非纯整数
 * （{@code -1.1}/{@code -A}/{@code -1-1}）一律不认。一张都没有 → 无效（缺图）。
 *
 * <p><b>每轮最多成功发布一条</b>（控制放出节奏）；<b>失败分两级</b>：
 * <ul>
 *   <li><b>轮级</b>（{@link LarkContentClient.LarkApiException}：token/读表/云盘/下载等传输
 *       与平台层失败，及作者池穷尽这类系统性配置错）——立即中止本轮、只记日志、
 *       <b>不写任何脏状态</b>，下小时自动重试；</li>
 *   <li><b>行级</b>（缺图/编号非法/文案超长/邮箱不匹配等内容性问题）——记 FAILED、
 *       回写「上传状态=无效」+「备注=原因」后顺延下一行；连续 {@value #MAX_ROW_FAILURES} 行失败
 *       触发熔断中止本轮，防止系统性问题把整表涂成失败。</li>
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
    /** 内容编号白名单（落 lark_content_publishes.content_code varchar(32)，拼进 OSS objectKey）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** 图片编号前缀白名单：不得含 {@code -}（{@code -} 后是序号），拼进 OSS objectKey 与公网 URL。 */
    private static final Pattern IMAGE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");
    /** 云盘文件名：{前缀}-{整数}.jpg；组 1=前缀，组 2=序号。 */
    private static final Pattern IMAGE_FILE_PATTERN = Pattern.compile("([A-Za-z0-9_]{1,32})-(\\d{1,6})\\.jpg");
    /** 「发布账号(邮箱)」格式（users.email varchar(320)）。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,255}");
    /** 文案上限：content_posts.text varchar(1000)。 */
    static final int MAX_TEXT_LENGTH = 1000;
    /** 单帖图片上限：ContentPostCreateRequest.imageUrls ≤ 9。 */
    static final int MAX_IMAGES = 9;
    /** 行级失败熔断阈值：连续失败到这个数就中止本轮（多半是系统性问题，别再涂表）。 */
    static final int MAX_ROW_FAILURES = 5;
    /** 回写「上传状态」用词。 */
    static final String STATUS_INVALID = "无效";

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

        Long randomAuthor = null; // 懒选：全是指定邮箱行时不必碰作者池。
        Map<String, String> folder = null; // 懒加载：全是补回写/重复行时不必列文件夹。
        int rowFailures = 0;
        for (LarkRowParser.Row row : pending) {
            int firstRow = firstRowOf.get(row.contentCode());
            if (firstRow != row.sheetRowNumber()) {
                // 重复编号行：只回写提醒（用快照行号——按编号定位会指到首行），绝不碰 DB。
                writeStatusQuietly(cols, row.sheetRowNumber(), STATUS_INVALID, "",
                        "内容编号与第" + firstRow + "行重复");
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
                long authorId;
                if (row.email().isBlank()) {
                    if (randomAuthor == null) {
                        // 作者池穷尽属系统性配置错 → 直接抛（轮级），绝不把行涂成失败。
                        randomAuthor = pickAuthor();
                    }
                    authorId = randomAuthor;
                } else {
                    authorId = resolveAuthorByEmail(row.email()); // 匹配不到 → IllegalStateException（行级：无效）
                }
                if (folder == null) {
                    folder = client.listFolderFiles();
                }
                long postId = publishRow(cols, row, folder, authorId, existing.orElse(null));
                log.info("Lark 发帖成功 code={} postId={}", row.contentCode(), postId);
                return; // 每轮恰好一条，发成即收工。
            } catch (LarkContentClient.LarkApiException e) {
                throw e; // 传输/平台层失败：轮级——不标行、不回写，交 syncOnce 记日志。
            } catch (AuthorPoolExhausted e) {
                throw e; // 系统性配置错：轮级。
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

    /** 发布一行：按前缀收集云盘图（按序号升序）→ 下图 → OSS →（事务内）免审发布 + DB 状态机 → 回写。 */
    private long publishRow(LarkRowParser.Columns cols, LarkRowParser.Row row,
            Map<String, String> folder, long authorId, LarkContentPublish existing) {
        List<String> fileNames = resolveImageFiles(row.imageCodes(), folder);
        List<String> imageUrls = new ArrayList<>();
        for (String fileName : fileNames) {
            byte[] bytes = client.downloadFile(folder.get(fileName)); // 传输失败/非图片 → LarkApiException（轮级）
            String key = mediaProps.getOss().normalizedKeyPrefix()
                    + "public/lark-content/" + row.contentCode() + "/" + fileName;
            imageUrls.add(oss.putPublicObjectWithAcl(key, bytes, "image/jpeg"));
        }

        ContentPostCreateRequest req = new ContentPostCreateRequest(
                mapCategory(row.category()), null, blankToNull(row.text()), imageUrls);
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

    /**
     * 按前缀在云盘文件夹里收集 {@code {前缀}-{整数}.jpg}，按整数升序；多前缀时按前缀在表格中的顺序拼接。
     * 某前缀一张都没有 → 缺图（行级）；总数超 {@value #MAX_IMAGES} → 行级。
     */
    static List<String> resolveImageFiles(List<String> prefixes, Map<String, String> folder) {
        List<String> out = new ArrayList<>();
        for (String prefix : prefixes) {
            TreeMap<Integer, String> ordered = new TreeMap<>();
            for (String fileName : folder.keySet()) {
                Matcher m = IMAGE_FILE_PATTERN.matcher(fileName);
                if (m.matches() && m.group(1).equals(prefix)) {
                    ordered.put(Integer.parseInt(m.group(2)), fileName);
                }
            }
            if (ordered.isEmpty()) {
                throw new IllegalStateException("缺图：云盘找不到 " + prefix + "-<序号>.jpg");
            }
            out.addAll(ordered.values());
        }
        if (out.size() > MAX_IMAGES) {
            throw new IllegalStateException("图片超过 " + MAX_IMAGES + " 张（云盘匹配到 " + out.size() + " 张）");
        }
        return out;
    }

    /** 「内容分类」→ 类型。空=DAILY；非法值返回 null（validateRow 已先拦，这里兜底）。 */
    static ContentType mapCategory(String category) {
        String c = category == null ? "" : category.trim().toLowerCase();
        return switch (c) {
            case "", "moment", "daily" -> ContentType.DAILY;
            case "knowledge" -> ContentType.KNOWLEDGE;
            default -> null;
        };
    }

    /** 内容性校验（下载前拦截）。返回失败原因，合法返回 null。字段上限对齐生产库列定义。 */
    private static String validateRow(LarkRowParser.Row row) {
        if (!CODE_PATTERN.matcher(row.contentCode()).matches()) {
            return "内容编号非法：仅限字母/数字/_/-，最长 32 字符";
        }
        if (mapCategory(row.category()) == null) {
            return "内容分类只能填 Moment 或 Knowledge（留空按 Moment）";
        }
        for (String code : row.imageCodes()) {
            if (code.contains("-")) {
                return "图片编号不得带「-」及序号（应填前缀如 DR260823001，云盘文件为 DR260823001-1.jpg）：" + brief(code);
            }
            if (!IMAGE_PREFIX_PATTERN.matcher(code).matches()) {
                return "图片编号非法：仅限字母/数字/_，最长 32 字符：" + brief(code);
            }
        }
        if (String.join(",", row.imageCodes()).length() > 255) {
            return "图片编号列表过长（最长 255 字符）";
        }
        String text = row.text();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            return "文案超过 " + MAX_TEXT_LENGTH + " 字（当前 " + text.length() + " 字）";
        }
        if ((text == null || text.isBlank()) && row.imageCodes().isEmpty()) {
            return "文案与图片均为空";
        }
        String email = row.email();
        if (!email.isBlank() && (email.length() > 320 || !EMAIL_PATTERN.matcher(email).matches())) {
            return "发布账号不是合法邮箱"; // 不回显邮箱：reason 会进日志与 DB（PII 红线）
        }
        return null;
    }

    /** 作者池穷尽——系统性配置错，走轮级。 */
    static final class AuthorPoolExhausted extends IllegalStateException {
        AuthorPoolExhausted() {
            super("虚拟作者池无可用账号");
        }
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
        throw new AuthorPoolExhausted();
    }

    /**
     * 「发布账号(邮箱)」→ 用户 id。只接受 USER 角色（与全库其他邮箱查询同口径，排除 ADMIN shim 行）且 ACTIVE；
     * 真实号/虚拟号不限——官方运营号是真实注册账号（2026-08-28 拍板）。表格是运营可信输入，填谁就以谁发布。
     * 匹配不到 → 行级无效。reason 不带邮箱（会进日志与 DB）。
     */
    private long resolveAuthorByEmail(String email) {
        Optional<User> u = users.findByEmailAndRole(email, Role.USER);
        if (u.isEmpty() || u.get().getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("发布账号未匹配到有效用户");
        }
        return u.get().getId();
    }

    /** 行级失败：DB 记 FAILED（绝不降级已 PUBLISHED 的记录）+ 回写「无效」+ 备注原因。 */
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
        writeBackByCode(cols, row.contentCode(), STATUS_INVALID, "", brief(reason));
    }

    /** 回写「已发布 <WIB 时间>」+ 作者昵称，备注清空。发布已成，任何失败只警告（下轮补）。 */
    private void writeBackPublished(LarkRowParser.Columns cols, String contentCode, long authorId) {
        try {
            String nickname = users.findById(authorId).map(User::getNickname).orElse("");
            String status = "已发布 " + ZonedDateTime.now(WIB).format(WIB_FMT) + " WIB";
            writeBackByCode(cols, contentCode, status, nickname != null ? nickname : "", "");
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 code={} cause={}", contentCode, e.getClass().getSimpleName());
        }
    }

    /**
     * 按内容编号重定位后回写（防运营中途插/删行标错行）。定位不到（行已被删）则跳过——
     * 宁可漏写等下轮，也不按过期快照写错行。
     */
    private void writeBackByCode(LarkRowParser.Columns cols, String contentCode,
            String status, String account, String note) {
        try {
            Optional<Integer> located = client.findRowByCode(
                    LarkRowParser.Columns.letter(cols.code()), contentCode);
            if (located.isEmpty()) {
                log.warn("Lark 回写跳过：表中已找不到编号 {}", contentCode);
                return;
            }
            writeCells(cols, located.get(), status, account, note);
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 code={} cause={}", contentCode, e.getClass().getSimpleName());
        }
    }

    /** 直接按行号静默回写（仅重复编号行使用——按编号定位会指到首行）。 */
    private void writeStatusQuietly(LarkRowParser.Columns cols, int sheetRowNumber,
            String status, String account, String note) {
        try {
            writeCells(cols, sheetRowNumber, status, account, note);
        } catch (RuntimeException e) {
            log.warn("Lark 回写失败 row={} cause={}", sheetRowNumber, e.getClass().getSimpleName());
        }
    }

    private void writeCells(LarkRowParser.Columns cols, int sheetRowNumber,
            String status, String account, String note) {
        client.writeCell(LarkRowParser.Columns.letter(cols.status()), sheetRowNumber, status);
        client.writeCell(LarkRowParser.Columns.letter(cols.account()), sheetRowNumber, account);
        client.writeCell(LarkRowParser.Columns.letter(cols.note()), sheetRowNumber, note);
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
