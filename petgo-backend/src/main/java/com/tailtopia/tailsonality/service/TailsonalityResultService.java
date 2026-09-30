package com.tailtopia.tailsonality.service;

import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.domain.TailsonalityScorer;
import com.tailtopia.tailsonality.dto.TailsonalityResultListResponse;
import com.tailtopia.tailsonality.dto.TailsonalityResultResponse;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tailsonality 结果：提交 / 列表 / 单条（V1.3.2 Story 2.1 · AC4 / AC5）。
 *
 * <p>提交是<b>唯一写入口</b>，中途不落任何进度（C-5）；重测 = 再提交一次，新行新 token，旧行不动。
 */
@Service
public class TailsonalityResultService {

    /** 本版本结果文案内容版本（FR-117.19）。 */
    static final int CONTENT_VERSION = 1;

    private static final String INVALID_ANSWERS = "答案不完整或取值非法";

    private final TailsonalityResultRepository results;
    private final PetProfileQueryService pets;
    private final TailsonalityTokenGenerator tokens;
    private final Clock clock;

    @Autowired
    public TailsonalityResultService(TailsonalityResultRepository results, PetProfileQueryService pets,
            TailsonalityTokenGenerator tokens) {
        this(results, pets, tokens, Clock.systemUTC());
    }

    TailsonalityResultService(TailsonalityResultRepository results, PetProfileQueryService pets,
            TailsonalityTokenGenerator tokens, Clock clock) {
        this.results = results;
        this.pets = pets;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public TailsonalityResultResponse submit(long userId, Map<String, ?> body) {
        OwnedPetRef pet = requirePet(userId);
        Map<String, Integer> answers = normalize(body);
        TailsonalityQuestionSet set = TailsonalityCatalog.forPetType(pet.petType());
        TailsonalityCode code = TailsonalityScorer.score(set, answers);
        TailsonalityResult saved = results.save(TailsonalityResult.create(tokens.generate(), pet.petId(), userId,
                set, answers, code, CONTENT_VERSION, Instant.now(clock)));
        // resultIndex 按新行在 (created_at, id) 序里的位置算，不用「总条数」：并发提交（双击重试 / 两台设备）时
        // 总条数可能已含对方的行，提交响应会与之后列表 / 单条读到的序号不一致。
        return TailsonalityResultResponse.of(saved, indexOf(pet.petId(), saved.getId()));
    }

    @Transactional(readOnly = true)
    public TailsonalityResultListResponse list(long userId) {
        OwnedPetRef pet = requirePet(userId);
        List<TailsonalityResult> rows = results.findByPetProfileIdOrderByCreatedAtDescIdDesc(pet.petId());
        List<TailsonalityResultResponse> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            // 新 → 旧排列；最旧一条序号 1。
            items.add(TailsonalityResultResponse.of(rows.get(i), rows.size() - i));
        }
        return new TailsonalityResultListResponse(items);
    }

    /** token 不存在或不属于当前用户当前宠物 → 同一个 404（防枚举）。 */
    @Transactional(readOnly = true)
    public TailsonalityResultResponse get(long userId, String token) {
        OwnedPetRef pet = requirePet(userId);
        TailsonalityResult row = results.findByPublicTokenAndPetProfileId(token, pet.petId())
                .orElseThrow(() -> AppException.notFound("结果不存在"));
        return TailsonalityResultResponse.of(row, indexOf(pet.petId(), row.getId()));
    }

    /** 1 起序号：该宠物结果按 created_at 升序（同刻按 id）中本行的位置；与 {@link #list} 同一排序口径。 */
    int indexOf(long petId, long resultId) {
        List<TailsonalityResult> rows = results.findByPetProfileIdOrderByCreatedAtDescIdDesc(petId);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId() == resultId) {
                return rows.size() - i;
            }
        }
        throw new IllegalStateException("result row not visible in its own transaction");
    }

    private OwnedPetRef requirePet(long userId) {
        return pets.findOwnedPet(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    /**
     * 键集合必须<b>恰好</b>等于 18 个题号，值必须是 0..3 的整数；否则 422（固定文案，不回显原值）。
     *
     * <p>值只认 JSON 整数：字符串 / 小数也判 422 —— Jackson 默认会把 {@code 1.5} 截成 1、{@code "1"} 转成 1，
     * 那等于静默改写用户答案。
     */
    static Map<String, Integer> normalize(Map<String, ?> body) {
        if (body == null || body.size() != TailsonalityCatalog.QUESTION_IDS.size()
                || !new HashSet<>(TailsonalityCatalog.QUESTION_IDS).equals(body.keySet())) {
            throw AppException.validation(INVALID_ANSWERS);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String q : TailsonalityCatalog.QUESTION_IDS) {
            Object v = body.get(q);
            if (!(v instanceof Integer i) || i < 0 || i > 3) {
                throw AppException.validation(INVALID_ANSWERS);
            }
            out.put(q, i);
        }
        return out;
    }
}
