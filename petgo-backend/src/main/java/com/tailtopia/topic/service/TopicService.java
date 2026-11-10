package com.tailtopia.topic.service;

import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.topic.domain.TopicNameRules;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicToken;
import com.tailtopia.topic.domain.TopicType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 话题写入（V1.4.2 social-topic · Story 4.1 · D-2 · AC4）：发帖时把正文里的 {@code #话题} 写成关联，没有的用户话题顺手新建。
 *
 * <h2>依赖方向（§5.3）</h2>
 * content 单向调 topic：{@code ContentService → TopicService}。本类<b>不得</b>注入任何 content service，
 * 只用 {@link JdbcTemplate}（可读写 {@code topics} / {@code content_topics}）。
 *
 * <h2>并发安全（D-2）</h2>
 * 新建走 {@code INSERT … ON CONFLICT (name_norm) DO NOTHING} 后<b>再查一次</b>：READ COMMITTED 下第二个并发事务在唯一索引上
 * 等第一个提交，之后 DO NOTHING、新语句读到已提交行。一帖多个话题时<b>按标准名排序</b>依次查 / 插，
 * 所有事务加锁顺序一致，反序并发（「#aa #bb」对「#bb #aa」）不会死锁。<b>不要</b>改成「先 findByNameNorm 再 save」——并发时第二个抛唯一约束、整帖失败。
 *
 * <h2>事务</h2>
 * 与发帖<b>同一事务</b>（{@code REQUIRED}）：写入失败不吞，整帖失败（丢关联会让帖子永远不进话题页）；
 * 整帖回滚时新建话题随之回滚。
 */
@Service
public class TopicService {

    private static final String SELECT_BY_NORM = "SELECT id, type, status FROM topics WHERE name_norm = ?";

    private static final String INSERT_USER_TOPIC = """
            INSERT INTO topics (name_display, name_norm, type, status)
            VALUES (?, ?, 'USER', 'ACTIVE')
            ON CONFLICT (name_norm) DO NOTHING
            """;

    private static final String INSERT_LINK = """
            INSERT INTO content_topics (post_id, topic_id)
            VALUES (?, ?)
            ON CONFLICT (post_id, topic_id) DO NOTHING
            """;

    private static final RowMapper<TopicRow> ROW = (rs, n) -> new TopicRow(
            rs.getLong("id"),
            TopicType.valueOf(rs.getString("type")),
            TopicStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public TopicService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按正文写话题关联。
     *
     * <ul>
     *   <li>非 {@code PUBLIC} 或正文为空 → 空表：<b>不写关联、不新建话题</b>（私密 Diary 带话题也不写）；</li>
     *   <li>取 {@link TopicNameRules#effectiveDistinct}（前 5 个不同标准名，纯文本规则，与库里状态无关）；</li>
     *   <li>不存在 → 以本帖首次写法新建 USER / ACTIVE；已存在 → 显示写法<b>一律不改</b>；</li>
     *   <li>{@code DISABLED} → 跳过（不写关联、不在返回里，也<b>不补位</b>第 6 个）；{@code ACTIVE} / {@code HIDDEN} → 写关联。</li>
     * </ul>
     *
     * @return 实际写入的关联（按正文出现顺序）；调用方据此发事件 {@code topicIds}、回发帖响应 {@code topics}
     */
    @Transactional
    public List<AttachedTopic> attach(long postId, String text, ContentVisibility visibility) {
        if (visibility != ContentVisibility.PUBLIC || text == null || text.isBlank()) {
            return List.of();
        }
        List<TopicToken> tokens = TopicNameRules.effectiveDistinct(text);
        if (tokens.isEmpty()) {
            return List.of();
        }
        // 🔴 防死锁：查 / 插 / 再查一律按标准名排序执行，保证所有事务对唯一索引的加锁顺序一致
        // （甲「#aa #bb」、乙「#bb #aa」同时新建时，按出现顺序插入会互相等待 → 一帖死锁 5xx）。
        // 写 content_topics 与组装返回值仍按正文出现顺序（content_topics 主键含 post_id，跨帖不冲突）。
        Map<String, Resolved> resolved = new HashMap<>();
        tokens.stream()
                .sorted(Comparator.comparing(TopicToken::norm))
                .forEach(t -> resolved.put(t.norm(), resolve(t)));
        List<AttachedTopic> out = new ArrayList<>(tokens.size());
        for (TopicToken t : tokens) {
            Resolved r = resolved.get(t.norm());
            TopicRow row = r.row();
            if (row.status() == TopicStatus.DISABLED) {
                continue;
            }
            jdbc.update(INSERT_LINK, postId, row.id());
            out.add(new AttachedTopic(row.id(), t.norm(), row.type() == TopicType.OFFICIAL, r.created()));
        }
        return List.copyOf(out);
    }

    /** 查 → 不存在则 {@code INSERT … ON CONFLICT DO NOTHING} → 再查（D-2 并发安全）。 */
    private Resolved resolve(TopicToken t) {
        TopicRow row = findByNorm(t.norm());
        if (row != null) {
            return new Resolved(row, false);
        }
        // 显示写法存 NFC（不改大小写）：码点数与规则口径一致，落得进 VARCHAR(30)。
        String display = Normalizer.normalize(t.display(), Normalizer.Form.NFC);
        boolean created = jdbc.update(INSERT_USER_TOPIC, display, t.norm()) == 1;
        row = findByNorm(t.norm());
        if (row == null) {
            // 理论不可达（刚插入或冲突方已提交）；宁可整帖失败也不静默丢关联。
            throw new IllegalStateException("topic row missing after upsert");
        }
        return new Resolved(row, created);
    }

    private record Resolved(TopicRow row, boolean created) {
    }

    private TopicRow findByNorm(String norm) {
        List<TopicRow> rows = jdbc.query(SELECT_BY_NORM, ROW, norm);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询投影：只取写关联要用的三列。 */
    record TopicRow(long id, TopicType type, TopicStatus status) {
    }
}
