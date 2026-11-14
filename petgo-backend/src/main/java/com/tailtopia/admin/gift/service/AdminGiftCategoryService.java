package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftCategoryForm;
import com.tailtopia.admin.gift.dto.GiftCategoryRow;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftCategoryRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台礼物分类（V1.5.0 gift-pack Story 1-2 · AB-43 · D-1）。
 *
 * <ul>
 *   <li>只下线不删除；分类下还有在线礼物时不能下线（服务端再查一次，K4 置灰只是前端提示）。</li>
 *   <li>名称 EN / ID 各自与已有分类（含已下线）不分大小写唯一；撞库级唯一索引（并发）也落成同一条 422。</li>
 *   <li>审计与业务写入同一事务；无真变化不写库、不写审计。</li>
 * </ul>
 */
@Service
public class AdminGiftCategoryService {

    private final GiftCategoryRepository categories;
    private final GiftCatalogRepository catalog;
    private final AdminAuditService audit;

    public AdminGiftCategoryService(GiftCategoryRepository categories, GiftCatalogRepository catalog,
            AdminAuditService audit) {
        this.categories = categories;
        this.catalog = catalog;
        this.audit = audit;
    }

    /** 校验通过的分类字段。 */
    record Parsed(String nameEn, String nameId, int sortOrder) {
    }

    // ——————————————————— 读 ———————————————————

    /** 分类页签（K1）：全部分类（含已下线）按排序，带在线 / 全部礼物数（目录百级，一次取全表）。 */
    @Transactional(readOnly = true)
    public List<GiftCategoryRow> list() {
        Map<Long, long[]> counts = new HashMap<>();
        for (GiftCatalogItem g : catalog.findAll()) {
            if (g.getCategoryId() == null) {
                continue;
            }
            long[] c = counts.computeIfAbsent(g.getCategoryId(), k -> new long[2]);
            c[1]++;
            if (g.getStatus() == GiftCatalogStatus.ONLINE) {
                c[0]++;
            }
        }
        List<GiftCategoryRow> rows = new ArrayList<>();
        for (GiftCategory c : categories.findAllByOrderBySortOrderAscIdAsc()) {
            long[] n = counts.getOrDefault(c.getId(), new long[2]);
            rows.add(GiftCategoryRow.of(c, n[0], n[1]));
        }
        return rows;
    }

    /** 礼物抽屉的分类下拉：只列在线分类、按排序。 */
    @Transactional(readOnly = true)
    public List<GiftCategory> onlineCategories() {
        return categories.findAllByStatusOrderBySortOrderAscIdAsc(GiftCategoryStatus.ONLINE);
    }

    /** 礼物筛选下拉 / 礼物表「分类」列：全部分类（含已下线）。 */
    @Transactional(readOnly = true)
    public List<GiftCategory> allCategories() {
        return categories.findAllByOrderBySortOrderAscIdAsc();
    }

    /** 分类详情抽屉（K4 / K7 / K8）。不存在 → 404。 */
    @Transactional(readOnly = true)
    public GiftCategoryRow detail(long id) {
        GiftCategory c = require(id);
        return GiftCategoryRow.of(c, catalog.countByCategoryIdAndStatus(id, GiftCatalogStatus.ONLINE),
                catalog.countByCategoryId(id));
    }

    // ——————————————————— 写 ———————————————————

    /** 新建分类（K3）：默认在线。返回新分类 id。 */
    @Transactional
    public long create(long adminId, GiftCategoryForm form) {
        Parsed p = validate(form);
        List<GiftCategory> all = categories.findAll();
        checkDuplicate(all, null, p);
        GiftCategory c = GiftCategory.create(p.nameEn(), p.nameId(), p.sortOrder(), adminId);
        c = saveChecked(c, p);
        audit.record(adminId, AuditActions.GIFT_CATEGORY_CREATED, "gift_categories", String.valueOf(c.getId()),
                "name_en=" + p.nameEn() + "; name_id=" + p.nameId() + "; sort_order=" + p.sortOrder());
        return c.getId();
    }

    /**
     * 编辑分类（K4 / K7：已下线的分类仍可改名 / 改排序）。
     *
     * @return 是否真的有改动
     */
    @Transactional
    public boolean update(long adminId, long id, GiftCategoryForm form) {
        GiftCategory c = require(id);
        Parsed p = validate(form);
        List<String> changes = new ArrayList<>();
        diff(changes, "name_en", c.getNameEn(), p.nameEn());
        diff(changes, "name_id", c.getNameId(), p.nameId());
        diff(changes, "sort_order", c.getSortOrder(), p.sortOrder());
        if (changes.isEmpty()) {
            return false;
        }
        List<GiftCategory> all = categories.findAll();
        checkDuplicate(all, id, p);
        c.edit(p.nameEn(), p.nameId(), p.sortOrder(), adminId);
        saveChecked(c, p);
        audit.record(adminId, AuditActions.GIFT_CATEGORY_UPDATED, "gift_categories", String.valueOf(id),
                String.join("; ", changes));
        return true;
    }

    /**
     * 上 / 下线（K6 / K7）。已是目标状态 → 幂等成功、不写审计；下线时分类下还有在线礼物 → 422。
     *
     * @return 是否真的改了状态
     */
    @Transactional
    public boolean setOnline(long adminId, long id, boolean online) {
        GiftCategory c = require(id);
        GiftCategoryStatus target = online ? GiftCategoryStatus.ONLINE : GiftCategoryStatus.OFFLINE;
        if (c.getStatus() == target) {
            return false;
        }
        if (!online) {
            long onlineGifts = catalog.countByCategoryIdAndStatus(id, GiftCatalogStatus.ONLINE);
            if (onlineGifts > 0) {
                throw AppException.validation("还有 " + onlineGifts + " 个在线礼物，不能下线这个分类")
                        .code("admin.v150.giftCategory.offlineBlocked", onlineGifts);
            }
        }
        GiftCategoryStatus before = c.getStatus();
        c.setStatus(target, adminId);
        categories.save(c);
        audit.record(adminId, online ? AuditActions.GIFT_CATEGORY_ONLINED : AuditActions.GIFT_CATEGORY_OFFLINED,
                "gift_categories", String.valueOf(id), "status " + before + "→" + target);
        return true;
    }

    // ——————————————————— 校验 ———————————————————

    /** 名称必填、trim 后 ≤ 40；排序 0 ~ 9999（空 = 0，同礼物）。 */
    static Parsed validate(GiftCategoryForm form) {
        String nameEn = trim(form.nameEn());
        String nameId = trim(form.nameId());
        if (nameEn.isEmpty() || nameId.isEmpty()) {
            throw AppException.validation("请填写 EN 与 ID 名称").code("admin.err.gift.nameRequired");
        }
        if (nameEn.length() > GiftCatalogRules.CATEGORY_NAME_MAX || nameId.length() > GiftCatalogRules.CATEGORY_NAME_MAX) {
            throw AppException.validation("名称最多 40 个字").code("admin.err.gift.nameTooLong");
        }
        int sortOrder = 0;
        String rawSort = trim(form.sortOrder());
        if (!rawSort.isEmpty()) {
            Long so = AdminGiftCatalogService.parseLong(rawSort);
            if (so == null || so < 0 || so > GiftCatalogRules.SORT_ORDER_MAX) {
                throw AppException.validation("排序须为 0 ~ 9999 的整数").code("admin.err.gift.sortOrderRange");
            }
            sortOrder = so.intValue();
        }
        return new Parsed(nameEn, nameId, sortOrder);
    }

    /** 与已有分类（含已下线、不分大小写）重名 → 422（K5 句式；EN 先于 ID 判）。 */
    static void checkDuplicate(List<GiftCategory> all, Long selfId, Parsed p) {
        for (GiftCategory c : all) {
            if (Objects.equals(c.getId(), selfId)) {
                continue;
            }
            if (lower(c.getNameEn()).equals(lower(p.nameEn()))) {
                throw duplicate("EN", p.nameEn(), c);
            }
        }
        for (GiftCategory c : all) {
            if (Objects.equals(c.getId(), selfId)) {
                continue;
            }
            if (lower(c.getNameId()).equals(lower(p.nameId()))) {
                throw duplicate("ID", p.nameId(), c);
            }
        }
    }

    private static AppException duplicate(String which, String name, GiftCategory conflict) {
        return AppException.validation("名称 " + which + "「" + name + "」已被分类「" + conflict.getNameEn() + "」使用")
                .code("admin.v150.giftCategory.err.duplicate", which, name, conflict.getNameEn());
    }

    /** 落库并 flush：并发撞 {@code lower()} 唯一索引 → 同一条重名 422（不让约束以 500 露出）。 */
    private GiftCategory saveChecked(GiftCategory c, Parsed p) {
        try {
            return categories.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            // 事务已中止，不能再查库：按本次提交的 EN 名报重名（并发竞态下的兜底，正常路径已在 checkDuplicate 拦住）。
            throw AppException.validation("名称已被其他分类使用").code("admin.v150.giftCategory.err.duplicate",
                    "EN", p.nameEn(), p.nameEn());
        }
    }

    private GiftCategory require(long id) {
        return categories.findById(id).orElseThrow(() -> AppException.notFound("分类不存在")
                .code("admin.v150.giftCategory.err.notFound"));
    }

    private static void diff(List<String> out, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            out.add(field + " " + oldVal + "→" + newVal);
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
