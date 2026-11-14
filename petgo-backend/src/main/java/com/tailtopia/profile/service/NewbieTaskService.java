package com.tailtopia.profile.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新手任务进度（V1.5.0 onboarding-recall Story 2.1 · FR-176 · OR-1）—— ≥ 1.5.0 客户端。
 *
 * <p>按<b>账号</b>算：①–⑥ 读 {@code user_newbie_marks}（做过即记、永不撤）；⑦ 按 WIB 注册日直接判定
 * （{@code 注册日 + 7 ≤ 今天}，不依赖标记，保证「不打开 App 也当天完成」与读到的一致）。<b>没宠物也返回 200</b>。
 * &lt; 1.5.0 客户端走 {@link LegacyNewbieTaskCompat}（OR-9）。
 */
@Service
public class NewbieTaskService {

    /** ⑦ 需要的天数。 */
    static final int ONE_WEEK_DAYS = 7;

    private final PetProfileRepository profiles;
    private final UserNewbieMarkDao marks;
    private final AccountQueryService accounts;
    private final ContentService contentService;
    private final LulusPemulaReader lulus;

    public NewbieTaskService(PetProfileRepository profiles, UserNewbieMarkDao marks, AccountQueryService accounts,
            ContentService contentService, LulusPemulaReader lulus) {
        this.profiles = profiles;
        this.marks = marks;
        this.accounts = accounts;
        this.contentService = contentService;
        this.lulus = lulus;
    }

    /** 当前用户 7 新手任务进度（固定顺序）。 */
    @Transactional(readOnly = true)
    public NewbieTaskResponse progress(long ownerId, Instant now) {
        Set<String> done = marks.keysOf(ownerId);
        long daysSinceSignup = accounts.createdAtOf(ownerId)
                .map(created -> Math.max(0, ChronoUnit.DAYS.between(wibDate(created), wibDate(now))))
                .orElse(0L);
        boolean oneWeekDone = daysSinceSignup >= ONE_WEEK_DAYS;

        List<NewbieTaskResponse.Item> items = new ArrayList<>(NewbieTask.ALL.size());
        int completed = 0;
        for (NewbieTask task : NewbieTask.ALL) {
            boolean ok = task == NewbieTask.ONE_WEEK ? oneWeekDone : done.contains(task.name());
            if (ok) {
                completed++;
            }
            items.add(new NewbieTaskResponse.Item(task.name(), ok));
        }

        List<PetProfile> pets = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId);
        int daysLeft = oneWeekDone ? 0 : (int) Math.max(1, ONE_WEEK_DAYS - daysSinceSignup);
        Long replyGoPostId = done.contains(NewbieTask.FIRST_REPLY.name())
                ? null
                : contentService.latestPostCommentedByOthers(ownerId).orElse(null);
        return new NewbieTaskResponse(items, completed, NewbieTask.ALL.size(), lulus.unlocked(ownerId, pets),
                !pets.isEmpty(), daysLeft, replyGoPostId);
    }

    static LocalDate wibDate(Instant at) {
        return at.atZone(ScheduleWindow.WIB).toLocalDate();
    }
}
