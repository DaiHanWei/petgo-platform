package com.tailtopia.admin.places.service;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.domain.PlaceType;
import com.tailtopia.admin.places.dto.PlaceDrawerView;
import com.tailtopia.admin.places.dto.PlaceDrawerView.CommentView;
import com.tailtopia.admin.places.dto.PlaceDrawerView.CommentsPage;
import com.tailtopia.admin.places.dto.PlaceDrawerView.PhotoView;
import com.tailtopia.admin.places.dto.PlaceFilter;
import com.tailtopia.admin.places.dto.PlaceRow;
import com.tailtopia.admin.places.dto.PlaceSummary;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B6 场所管理只读查询（V1.3.0 Story 5.2，AD-5 / AD-9）：列表（筛选 + 分页）、摘要条（单条聚合）、城市下拉（D-39）、详情抽屉。
 * <ul>
 * <li>所有查询 {@code deleted_at IS NULL}；关键词对 {@code name} / {@code address_text} 做 {@code ILIKE %q%}。</li>
 * <li>标记人 / 上传者 / 评论作者昵称整批经 {@link AccountQueryService#findAuthorViews}（注销 → 置灰标记），不逐条查。</li>
 * <li>照片 URL 由 {@link SignedUrlService} 现签短时效，<b>不落库、不进日志</b>（CLAUDE.md 护栏）。</li>
 * <li>写操作（编辑 / 下架 / 合并…）不在本类（Story 5.3）。</li>
 * </ul>
 */
@Service
public class AdminPlaceQueryService {

    static final int COMMENT_PAGE_SIZE = 20;

    private static final String WHERE = """
            p.deleted_at IS NULL
              AND (:q IS NULL OR p.name ILIKE :q OR p.address_text ILIKE :q)
              AND (:type IS NULL OR p.place_type = :type)
              AND (:status IS NULL OR p.status = :status)
              AND (:city IS NULL OR p.city = :city)
            """;

    public static final String LIST_SQL = "SELECT p.id FROM places p WHERE " + WHERE
            + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset";

    public static final String COUNT_SQL = "SELECT COUNT(*) FROM places p WHERE " + WHERE;

    /** 摘要条四格一条聚合（AC3）：上架数 · 今日新增（WIB 自然日）· 待处理举报数（筛选内场所的 PENDING 举报）· 累计打卡数。 */
    public static final String SUMMARY_SQL = """
            SELECT COUNT(*) FILTER (WHERE p.status = 'ACTIVE')                                          AS active_count,
                   COUNT(*) FILTER (WHERE (p.created_at AT TIME ZONE 'Asia/Jakarta')::date = :today)    AS today_new,
                   COALESCE(SUM((SELECT COUNT(*) FROM place_reports r WHERE r.place_id = p.id AND r.status = 'PENDING')), 0) AS pending_reports,
                   COALESCE(SUM(p.checkin_count), 0)                                                    AS checkins
            FROM places p
            WHERE
            """ + WHERE; // 文本块会剥尾随空格：WHERE 必须单独一行，否则拼成 WHEREp.deleted_at（复审 #1）

    static final String CITIES_SQL = "SELECT DISTINCT p.city FROM places p WHERE p.deleted_at IS NULL ORDER BY p.city";

    private final NamedParameterJdbcTemplate jdbc;
    private final PlaceRepository places;
    private final PlacePhotoRepository photos;
    private final PlaceCommentRepository comments;
    private final AccountQueryService accounts;
    private final SignedUrlService signedUrls;
    private final Messages msg;

    public AdminPlaceQueryService(NamedParameterJdbcTemplate jdbc, PlaceRepository places, PlacePhotoRepository photos,
            PlaceCommentRepository comments, AccountQueryService accounts, SignedUrlService signedUrls, Messages msg) {
        this.jdbc = jdbc;
        this.places = places;
        this.photos = photos;
        this.comments = comments;
        this.accounts = accounts;
        this.signedUrls = signedUrls;
        this.msg = msg;
    }

    /** 列表页数据：行 + 总数（创建时间倒序，每页 20）。 */
    public record ListResult(List<PlaceRow> rows, long total, boolean hasNext) {
    }

    @Transactional(readOnly = true)
    public ListResult search(PlaceFilter f) {
        MapSqlParameterSource p = params(f)
                .addValue("limit", PlaceFilter.PAGE_SIZE)
                .addValue("offset", (long) f.page() * PlaceFilter.PAGE_SIZE);
        List<Long> ids = jdbc.queryForList(LIST_SQL, p, Long.class);
        long total = jdbc.queryForObject(COUNT_SQL, params(f), Long.class);
        if (ids.isEmpty()) {
            return new ListResult(List.of(), total, false);
        }
        // 保序回读实体（≤20 行，一次 findAllById）
        Map<Long, Place> byId = places.findAllById(ids).stream().collect(Collectors.toMap(Place::getId, x -> x));
        List<Place> ordered = ids.stream().map(byId::get).filter(x -> x != null).toList();
        Map<Long, AuthorView> markers = authorViews(ordered.stream().map(Place::getMarkedByUserId).collect(Collectors.toSet()));
        List<PlaceRow> rows = new ArrayList<>(ordered.size());
        for (Place pl : ordered) {
            AuthorView m = markers.get(pl.getMarkedByUserId());
            rows.add(new PlaceRow(pl.getId(), pl.getPublicToken(), pl.getName(), pl.getPlaceType(), typeName(pl.getPlaceType()), pl.getTags(),
                    pl.getCity(), pl.getAddressText(), name(m, pl.getMarkedByUserId()), m != null && m.deleted(),
                    pl.getPhotoCount(), pl.getCommentCount(), pl.getCheckinCount(), pl.getRecommendCount(), pl.getNotRecommendCount(),
                    pl.getStatus(), pl.getMergedIntoId(), pl.getCreatedAt()));
        }
        return new ListResult(List.copyOf(rows), total, (long) (f.page() + 1) * PlaceFilter.PAGE_SIZE < total);
    }

    @Transactional(readOnly = true)
    public PlaceSummary summary(PlaceFilter f) {
        MapSqlParameterSource p = params(f).addValue("today", LocalDate.now(ScheduleWindow.WIB));
        return jdbc.query(SUMMARY_SQL, p, rs -> rs.next()
                ? new PlaceSummary(rs.getLong("active_count"), rs.getLong("today_new"), rs.getLong("pending_reports"), rs.getLong("checkins"))
                : new PlaceSummary(0, 0, 0, 0));
    }

    /** 城市下拉（D-39）：distinct city，未删场所。 */
    @Transactional(readOnly = true)
    public List<String> cities() {
        return jdbc.queryForList(CITIES_SQL, new MapSqlParameterSource(), String.class);
    }

    /** 详情抽屉（AC5）；不存在 / 已软删 → 404（{@code admin.err.places.notFound}）。{@code commentPage} 从 0 起。 */
    @Transactional(readOnly = true)
    public PlaceDrawerView drawer(long id, int commentPage) {
        return drawer(id, commentPage, true);
    }

    /** {@code withPhotos=false}：评论翻页只换评论区，不重签照片。 */
    @Transactional(readOnly = true)
    public PlaceDrawerView drawer(long id, int commentPage, boolean withPhotos) {
        Place pl = requirePlace(id);
        List<PlacePhoto> photoRows = withPhotos ? photos.findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtAsc(pl.getId()) : List.of();
        Page<PlaceComment> commentRows = comments.findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(pl.getId(),
                PageRequest.of(Math.max(commentPage, 0), COMMENT_PAGE_SIZE));
        Set<Long> userIds = new HashSet<>();
        userIds.add(pl.getMarkedByUserId());
        photoRows.forEach(x -> userIds.add(x.getUploaderUserId()));
        commentRows.forEach(x -> userIds.add(x.getAuthorUserId()));
        Map<Long, AuthorView> views = authorViews(userIds);
        AuthorView marker = views.get(pl.getMarkedByUserId());

        // 一次 signAll（一个 OSS 客户端）；凭证缺失 / 签名失败 → url=null 占位，不让照片区拖垮整个抽屉（复审 #3）。签名 URL 只进模板，不进日志 / 实体
        List<String> urls = signAllOrNulls(photoRows.stream().map(PlacePhoto::getObjectKey).toList());
        List<PhotoView> photoViews = new ArrayList<>(photoRows.size());
        for (int i = 0; i < photoRows.size(); i++) {
            PlacePhoto ph = photoRows.get(i);
            photoViews.add(new PhotoView(ph.getId(), urls.get(i), name(views.get(ph.getUploaderUserId()), ph.getUploaderUserId()), ph.getCreatedAt()));
        }
        List<CommentView> commentViews = new ArrayList<>();
        for (PlaceComment c : commentRows.getContent()) {
            AuthorView a = views.get(c.getAuthorUserId());
            commentViews.add(new CommentView(c.getId(), c.getBody(), name(a, c.getAuthorUserId()), a != null && a.deleted(), c.getAttitude(),
                    c.getCreatedAt()));
        }
        String mergedIntoName = pl.getStatus() == PlaceStatus.MERGED && pl.getMergedIntoId() != null
                ? places.findById(pl.getMergedIntoId()).map(Place::getName).orElse("#" + pl.getMergedIntoId()) : null;
        return new PlaceDrawerView(pl.getId(), pl.getPublicToken(), pl.getName(), pl.getPlaceType(), typeName(pl.getPlaceType()), pl.getTags(),
                pl.getDescription(), pl.getCity(), pl.getAddressText(), pl.getLat(), pl.getLng(), name(marker, pl.getMarkedByUserId()),
                marker != null && marker.deleted(), pl.getStatus(), pl.getMergedIntoId(), mergedIntoName, pl.getCreatedAt(), pl.getUpdatedAt(),
                pl.getPhotoCount(), pl.getCommentCount(), pl.getCheckinCount(), pl.getRecommendCount(), pl.getNotRecommendCount(),
                List.copyOf(photoViews), new CommentsPage(List.copyOf(commentViews), commentRows.getNumber(), commentRows.hasNext(),
                        commentRows.getTotalElements()));
    }

    private List<String> signAllOrNulls(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        try {
            return signedUrls.signAll(keys);
        } catch (AppException e) {
            List<String> nulls = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                nulls.add(null);
            }
            return nulls;
        }
    }

    /** 类型显示名：已知码走三语 key {@code admin.v130.places.type.<CODE>}，未知码原样（Dev Notes 软校验）。 */
    public String typeName(String code) {
        return PlaceType.isKnown(code) ? msg.get("admin.v130.places.type." + code) : code;
    }

    Place requirePlace(long id) {
        return places.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("场所不存在或已删除").code("admin.err.places.notFound"));
    }

    private Map<Long, AuthorView> authorViews(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, AuthorView> out = new LinkedHashMap<>();
        accounts.findAuthorViews(ids).forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static String name(AuthorView v, Long userId) {
        return v == null || v.nickname() == null || v.nickname().isBlank() ? "#" + userId : v.nickname();
    }

    private static MapSqlParameterSource params(PlaceFilter f) {
        return new MapSqlParameterSource()
                .addValue("q", f.q() == null ? null : "%" + escapeLike(f.q()) + "%", java.sql.Types.VARCHAR)
                .addValue("type", f.type(), java.sql.Types.VARCHAR)
                .addValue("status", f.status() == null ? null : f.status().name(), java.sql.Types.VARCHAR)
                .addValue("city", f.city(), java.sql.Types.VARCHAR);
    }

    /** 关键词里的 % / _ 按字面匹配。 */
    public static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
