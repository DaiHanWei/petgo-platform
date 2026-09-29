package com.tailtopia.content.autocomment;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.ai.GeminiException;
import com.tailtopia.shared.ai.PetCommentGenerator;
import com.tailtopia.shared.ai.PetCommentResult;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 自动评论（2026-09-29）：给「发帖满 2 小时、零评论、已过审的公开帖」用 AI 读首图写一句评论，
 * 以随机虚拟账号直接发出，每帖一行留档。
 *
 * <p>发评论复用 {@link CommentService#createTopLevel}（与后台暖贴同一个入口）：L1 敏感词、异步三方审核、
 * 通知楼主全部照常走——「直接发」指不经人工审批，不是跳过审核。
 *
 * <p>逐帖串行（产品拍板：for 循环依次发，不做随机错峰）。AI 调用<b>不在事务里</b>，只有「复查零评论 →
 * 发评论 → 写留档」三步在同一个事务里，保证发出去的评论一定有留档。单帖失败只记留档、不影响后续帖子。
 *
 * <p>{@link #preview} 给测试用：同一套取数与选号逻辑，只调 AI 返回结果，<b>不发评论、不写留档</b>。
 */
@Service
@EnableConfigurationProperties(AutoCommentProperties.class)
public class AutoCommentService {

    private static final Logger log = LoggerFactory.getLogger(AutoCommentService.class);

    /** 与后台暖贴、App 评论同一上限。 */
    static final int BODY_MAX = 200;
    private static final int SKIP_REASON_MAX = 200;

    private final AutoCommentLogRepository logs;
    private final ContentPostRepository posts;
    private final UserRepository users;
    private final PetProfileRepository pets;
    private final ContentSpeciesResolver speciesResolver;
    private final VirtualIdentityPicker identityPicker;
    private final CommentService commentService;
    private final PetCommentGenerator generator;
    private final AutoCommentProperties props;
    private final TransactionTemplate tx;

    public AutoCommentService(AutoCommentLogRepository logs, ContentPostRepository posts, UserRepository users,
            PetProfileRepository pets, ContentSpeciesResolver speciesResolver, VirtualIdentityPicker identityPicker,
            CommentService commentService, PetCommentGenerator generator, AutoCommentProperties props,
            TransactionTemplate tx) {
        this.logs = logs;
        this.posts = posts;
        this.users = users;
        this.pets = pets;
        this.speciesResolver = speciesResolver;
        this.identityPicker = identityPicker;
        this.commentService = commentService;
        this.generator = generator;
        this.props = props;
        this.tx = tx;
    }

    /** 一轮的统计（只有数量，不含任何帖子内容）。 */
    public record RunResult(int candidates, int posted, int skipped, int failed, long tookMs) {
    }

    // ------------------------------------------------------------------ 定时一轮

    public RunResult runOnce() {
        long start = System.currentTimeMillis();
        Instant since = props.getStartDate().atStartOfDay(ScheduleWindow.WIB).toInstant();
        Instant cutoff = Instant.now().minus(props.getMinPostAge());
        List<Long> candidates = logs.findCandidatePostIds(since, cutoff, props.getMaxAttempts(), props.getMaxPerRun());
        List<User> pool = virtualPool();
        int posted = 0;
        int skipped = 0;
        int failed = 0;
        for (Long postId : candidates) {
            AutoCommentStatus status = processOne(postId, pool);
            if (status == AutoCommentStatus.POSTED) {
                posted++;
            } else if (status == AutoCommentStatus.FAILED) {
                failed++;
            } else {
                skipped++;
            }
        }
        RunResult result = new RunResult(candidates.size(), posted, skipped, failed,
                System.currentTimeMillis() - start);
        log.info("auto comment run: candidates={} posted={} skipped={} failed={} tookMs={}",
                result.candidates(), result.posted(), result.skipped(), result.failed(), result.tookMs());
        return result;
    }

    /**
     * 处理一个帖子，返回最终状态；复查发现已有人评论时返回 null（不写留档——它已不是候选，下一轮也捞不到）。
     */
    AutoCommentStatus processOne(long postId, List<User> pool) {
        AutoCommentLog entry = logs.findByPostId(postId).orElseGet(() -> AutoCommentLog.forPost(postId));
        entry.beginAttempt();
        try {
            ContentPost post = posts.findById(postId).orElse(null);
            if (!commentable(post)) {
                return finish(entry, AutoCommentStatus.POST_GONE);
            }
            String imageUrl = firstImage(post);
            if (imageUrl == null) {
                return finish(entry, AutoCommentStatus.NO_IMAGE);
            }
            entry.setImageUrl(imageUrl);
            String species = speciesOf(post);
            User identity = identityPicker.pick(pool, post.getAuthorId(), species);
            if (identity == null) {
                return finish(entry, AutoCommentStatus.NO_IDENTITY);
            }
            entry.setVirtualUserId(identity.getId());
            entry.setModel(generator.model());
            entry.setPromptVersion(PetCommentGenerator.PROMPT_VERSION);

            long t0 = System.currentTimeMillis();
            PetCommentResult ai;
            try {
                ai = generator.generate(imageUrl, post.getText(), species, petNameOf(post));
            } catch (GeminiException e) {
                entry.setLatencyMs((int) (System.currentTimeMillis() - t0));
                entry.setErrorCode("GEMINI");
                return finish(entry, AutoCommentStatus.FAILED);
            }
            entry.setLatencyMs((int) (System.currentTimeMillis() - t0));
            if (ai.skip()) {
                entry.setSkipReason(truncate(ai.skipReason(), SKIP_REASON_MAX));
                return finish(entry, AutoCommentStatus.AI_SKIPPED);
            }
            String text = ai.comment() == null ? "" : ai.comment().strip();
            entry.setGeneratedText(truncate(text, 400));
            if (text.isEmpty() || text.length() > BODY_MAX) {
                entry.setErrorCode("AI_INVALID_LENGTH");
                return finish(entry, AutoCommentStatus.FAILED);
            }
            return post(entry, postId, identity.getId(), text);
        } catch (RuntimeException e) {
            log.warn("auto comment failed on one post: {}", e.getClass().getSimpleName());
            entry.setErrorCode(truncate(e.getClass().getSimpleName(), 64));
            return finish(entry, AutoCommentStatus.FAILED);
        }
    }

    /** 复查零评论 → 发评论 → 写留档，同一个事务：发出去的评论一定有留档，留档失败评论也回滚。 */
    private AutoCommentStatus post(AutoCommentLog entry, long postId, long identityId, String text) {
        try {
            return tx.execute(status -> {
                if (logs.hasLiveComment(postId)) {
                    return null;
                }
                CommentResponse created = commentService.createTopLevel(postId, identityId, text);
                entry.setCommentId(created.id());
                entry.setStatus(AutoCommentStatus.POSTED);
                logs.save(entry);
                return AutoCommentStatus.POSTED;
            });
        } catch (AppException e) {
            if (ErrorTypes.COMMENT_BLOCKED.equals(e.getType())) {
                return finish(entry, AutoCommentStatus.BLOCKED);
            }
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return finish(entry, AutoCommentStatus.POST_GONE);
            }
            entry.setErrorCode(truncate("APP_" + e.getStatus().value(), 64));
            return finish(entry, AutoCommentStatus.FAILED);
        }
    }

    private AutoCommentStatus finish(AutoCommentLog entry, AutoCommentStatus status) {
        entry.setStatus(status);
        try {
            logs.save(entry);
        } catch (RuntimeException e) {
            // 留档写失败（极少见，如并发实例抢写同一帖）只打日志，不影响本轮其余帖子。
            log.warn("auto comment log save failed: {}", e.getClass().getSimpleName());
        }
        return status;
    }

    // ------------------------------------------------------------------ 预览（测试用）

    /**
     * 单帖预览：返回 AI 会为这个帖子写的评论，<b>不发评论、不写留档</b>。不校验候选条件（方便拿任意帖子试提示词），
     * 但把帖子当前是否满足条件一并返回，便于判断。
     */
    public AutoCommentPreview preview(long postId) {
        ContentPost post = posts.findById(postId).orElse(null);
        if (post == null) {
            throw AppException.notFound("帖子不存在");
        }
        boolean hasComment = logs.hasLiveComment(postId);
        String imageUrl = firstImage(post);
        String species = speciesOf(post);
        String petName = petNameOf(post);
        User identity = identityPicker.pick(virtualPool(), post.getAuthorId(), species);
        AutoCommentPreview.Builder b = AutoCommentPreview.builder(postId)
                .post(post.getStatus().name(), post.getVisibility().name(), post.getDeletedAt() != null,
                        post.getCreatedAt(), hasComment)
                .input(imageUrl, species, petName)
                .identity(identity == null ? null : identity.getId(), identity == null ? null : identity.getNickname())
                .ai(generator.model(), PetCommentGenerator.PROMPT_VERSION, generator.live());
        if (imageUrl == null) {
            return b.outcome(AutoCommentStatus.NO_IMAGE.name(), null, null, null, null);
        }
        long t0 = System.currentTimeMillis();
        try {
            PetCommentResult ai = generator.generate(imageUrl, post.getText(), species, petName);
            long took = System.currentTimeMillis() - t0;
            return ai.skip()
                    ? b.outcome(AutoCommentStatus.AI_SKIPPED.name(), null, ai.skipReason(), took, null)
                    : b.outcome("OK", ai.comment(), null, took, null);
        } catch (GeminiException e) {
            return b.outcome(AutoCommentStatus.FAILED.name(), null, null, System.currentTimeMillis() - t0, "GEMINI");
        }
    }

    // ------------------------------------------------------------------ 公共小件

    /** 可用的虚拟账号：VIRTUAL + 启用 + 未注销。每轮查一次，逐帖复用。 */
    List<User> virtualPool() {
        return users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL).stream()
                .filter(User::isEnabled)
                .filter(u -> u.getDeletedAt() == null)
                .toList();
    }

    private static boolean commentable(ContentPost post) {
        return post != null && post.getDeletedAt() == null && post.getStatus() == PostStatus.PUBLISHED
                && post.getVisibility() == ContentVisibility.PUBLIC;
    }

    private static String firstImage(ContentPost post) {
        List<String> urls = post.getImageUrls();
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        String first = urls.get(0);
        return first == null || first.isBlank() ? null : first;
    }

    private String speciesOf(ContentPost post) {
        String species = speciesResolver.resolve(post.getSpeciesOverride(), post.getAuthorId()).species();
        return species == null ? ContentSpecies.GENERAL : species;
    }

    private String petNameOf(ContentPost post) {
        return post.getPetId() == null ? null
                : pets.findById(post.getPetId()).map(PetProfile::getName).orElse(null);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
