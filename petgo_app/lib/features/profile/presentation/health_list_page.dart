import 'package:dio/dio.dart';
import 'dart:async';

import 'package:flutter/material.dart';
// ScrollCacheExtent 未从 material 导出（Flutter 3.44），显式引 rendering。
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/design/online_pulse_dot.dart';
import '../data/current_pet_provider.dart';
import '../data/health_record_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/health_list_item.dart';
import '../domain/health_record_icons.dart';
import '../domain/milestone.dart';
import 'widgets/health_record_form.dart';
import '../data/pet_list_repository.dart';
import '../../pet_slot/presentation/pet_readonly_block.dart';

/// 健康记录列表页 `p-health-list`（Story 7.2 · FR-45B/45C · UX-DR10）。结构化记录（可编辑）+ 问诊存档
/// （🏥 只读）混排，`editable` 区分可点。新增/编辑结构化记录；创建 VACCINE/DEWORM 联动里程碑第四路径。
///
/// [presetAddType]（bug 20260729-406）：进页即自动弹出预选该类型的添加表单——健康类里程碑
/// （疫苗 M3/驱虫 M4）灰态徽章直跳本页用（`/profile/health?add=VACCINE`）。
class HealthListPage extends ConsumerStatefulWidget {
  const HealthListPage({
    super.key,
    this.presetAddType,
    this.focusRecordId,
    this.prefill = const HealthRecordPrefill(),
    this.safety = const [],
    this.notificationToken,
    this.fromConsult = false,
  });

  final String? presetAddType;

  /// V1.5.0 Story 3.1 · AC5：从健康提醒进入时的表单预填（`preset` / `dose` / `deworm` / `custom` / `dateEmpty`）。
  /// 本页只接住并原样传给表单；表单怎么用归 3.2。
  final HealthRecordPrefill prefill;

  /// 页顶警示框（`safety=PREGNANT,PERMETHRIN`，PRD FR-167）。非空时不自动弹表单。
  final List<String> safety;

  /// 健康通知 token（`nid`），3.4 归因用；本页只用它判断 `health_record_saved.source`。
  final String? notificationToken;

  /// 从 Kesehatan 页「+ Kontrol dokter」进来（`?from=consult`，3.5 传）→ `health_record_saved.entry = consult_page`。
  final bool fromConsult;

  /// 进页后要**定位并高亮**的结构化健康记录 id（`?focus=<id>`）。
  ///
  /// 由 Diary 时间线的类④胶囊点击注入（V1.1.2 Story 3.3 的遗留项，2026-08-04 补齐）：
  /// 在时间线上点的是「哪一条」，进列表就该看到那一条 —— 只跳到列表顶部等于让用户自己再找一遍，
  /// 记录多了根本找不到。
  final int? focusRecordId;

  /// V1.5.0：Kontrol dokter 排在 Lainnya 之前（PRD FR-161）。
  static const List<String> recordTypes = ['VACCINE', 'DEWORM', 'MENSTRUATION', 'NEUTER', 'VET_VISIT', 'CUSTOM'];

  /// 可被分类卡筛选的类型（PRD FR-172：Konsultasi 卡不筛选，Kontrol dokter 没有分类卡）。
  static const List<String> filterTypes = ['VACCINE', 'DEWORM', 'NEUTER', 'MENSTRUATION', 'CUSTOM'];

  /// Konsultasi 卡绿点是否脉冲（产品口径恒为 true）。
  ///
  /// ⚠️ 仅供测试：常驻 `repeat()` 动画让 `pumpAndSettle` 永不收敛，而本页被十余个测试文件以
  /// `pumpAndSettle` 驱动；`test/flutter_test_config.dart` 统一关掉，专门的用例再打开断言 `pulsing: true`。
  @visibleForTesting
  static bool debugConsultPulse = true;

  /// 警示框顺序：怀孕在前（AC5.2）。
  static const List<String> safetyOrder = ['PREGNANT', 'PERMETHRIN'];


  @override
  ConsumerState<HealthListPage> createState() => _HealthListPageState();
}

class _HealthListPageState extends ConsumerState<HealthListPage> {
  /// 被定位那一条的 key（用于 `ensureVisible` 滚动到它）。
  final GlobalKey _focusKey = GlobalKey();

  /// 高亮是否还亮着。数据到达后置 true，[_kFocusHighlightDuration] 后自动熄灭 ——
  /// 常亮会被误读成「选中态」，而这里只是「你刚才点的是这条」。
  bool _highlighting = false;
  bool _focusHandled = false;

  /// V1.5.0 Story 3.1 · AC4：当前分类筛选（[HealthListPage.filterTypes] 之一；null = 全部）。
  /// 纯页面状态：不改路由、不发请求、不记忆（新进页 / 切宠物一律无筛选）。
  String? _filter;
  int? _filterPetId;

  /// 当前宠物物种原值（表单按 CAT / DOG / 其他分支）；build 时刷新。
  String? _currentPetType;

  /// 路由带来的警示框，按 [HealthListPage.safetyOrder] 排序、去掉不认识的值。
  List<String> get _safety => [
        for (final s in HealthListPage.safetyOrder)
          if (widget.safety.contains(s)) s,
      ];

  /// 熄灭高亮的定时器。**必须在 dispose 里 cancel**：用户可能在 2 秒内就退出本页，
  /// 留一个悬空 timer 既没意义，也会让 widget test 报「Timer is still pending」。
  Timer? _highlightTimer;

  static const Duration _kFocusHighlightDuration = Duration(milliseconds: 2200);

  @override
  void dispose() {
    _highlightTimer?.cancel();
    super.dispose();
  }

  /// 目标条目还没挂载时的重试次数上限。
  ///
  /// 定位依赖目标条目的 `GlobalKey` 已经有 context，而 `ListView` 是懒 layout 的：条目排得靠后时
  /// 首帧拿不到。原先拿不到就静默 return、且已经把 `_focusHandled` 置了 true → **永不重试**，
  /// 表现正是这次要修的「点进来还停在顶部」（code-review 2026-08-04）。
  static const int _kFocusMaxAttempts = 6;

  int _focusAttempts = 0;

  /// 数据到达后滚到目标条目并高亮一次。只做一次（列表因编辑刷新时不重复抖动）。
  void _handleFocusOnce(List<HealthListItem> items) {
    final target = widget.focusRecordId;
    if (target == null || _focusHandled) return;
    final exists = items.any((it) => !it.isConsult && it.id == target);
    if (!exists) return; // 记录已被删 / 不在本档案：静默按普通列表处理，不报错打扰用户
    _focusHandled = true;
    _scheduleFocusAttempt();
  }

  /// 逐帧重试直到目标条目挂载（或放弃）。放弃时退化为「停在列表顶部」——与改动前同等表现，
  /// 不会更糟，但绝大多数情况下重试几帧就能拿到 context。
  void _scheduleFocusAttempt() {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final ctx = _focusKey.currentContext;
      if (ctx == null) {
        if (++_focusAttempts >= _kFocusMaxAttempts) return;
        _scheduleFocusAttempt();
        return;
      }
      await Scrollable.ensureVisible(
        ctx,
        duration: const Duration(milliseconds: 320),
        alignment: 0.25,
      );
      if (!mounted) return;
      setState(() => _highlighting = true);
      _highlightTimer = Timer(_kFocusHighlightDuration, () {
        if (mounted) setState(() => _highlighting = false);
      });
    });
  }

  @override
  void initState() {
    super.initState();
    final preset = widget.presetAddType;
    // V1.5.0 AC5.2：带警示框进来（提醒落页）不自动弹表单，底部按钮仍可开。
    if (preset != null && HealthListPage.recordTypes.contains(preset) && _safety.isEmpty) {
      // 首帧后弹（build 完成才有合法 sheet 挂载点）。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          // health_record_saved.source：带健康通知 token 进来 = 提醒；「+ Kontrol dokter」= 手动；
          // 其余 `?add=` 只来自里程碑入口。
          // （30 分钟归因 + notification_id / kind 归 Story 3.4。）
          _openForm(context, ref,
              presetType: preset,
              prefill: widget.prefill,
              source: widget.notificationToken != null
                  ? 'health_reminder'
                  : (widget.fromConsult ? 'manual' : 'milestone'),
              // 只有「+ Kontrol dokter」自动弹出的这一张算 consult_page；之后在本页再开的表单都是 health_page。
              entry: widget.fromConsult ? 'consult_page' : 'health_page');
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final ref = this.ref;
    final l10n = AppLocalizations.of(context);
    // V1.4.0 multi-pet（Story 1.5）：健康记录按当前宠物（family 键 petId）。
    final petAsync = watchEffectivePetId(ref);
    final petId = petAsync.value;
    final async = petId == null ? null : ref.watch(healthListProvider(petId));
    // V1.4.0 multi-pet Story 2.4 · AC5.8（E3）：多宠时标题带宠物名；当前宠物只读时「+」/「Tambah catatan」带锁、点了弹 E1。
    final pets = ref.watch(petListProvider).value ?? const <PetListItem>[];
    PetListItem? currentPet;
    for (final p in pets) {
      if (p.id == petId) currentPet = p;
    }
    final readOnly = currentPet?.isReadOnly ?? false;
    _currentPetType = currentPet?.petType;
    // AC4.5：筛选不记忆 —— 切宠物即清除（本页 State 不随宠物重建）。
    if (petId != _filterPetId) {
      _filterPetId = petId;
      _filter = null;
    }
    return Scaffold(
      backgroundColor: AppColors.cream2,
      // V1.5.0 Story 3.1 · AC3.1（PRD FR-172「Top-right buttons removed」）：AppBar 只留返回与标题。
      // 发起问诊入口 = Konsultasi 分类卡（push /triage）；新增入口 = 底部「Tambah catatan」。
      appBar: AppBar(
        title: Text(pets.length > 1 && currentPet != null
            ? l10n.petSlotHealthTitleNamed(currentPet.name)
            : l10n.healthListTitle),
      ),
      body: async == null
          // 当前宠物还没定下来 / 取不到 / 没有宠物（与后端 404「尚未创建宠物档案」同一空态）。
          ? (petAsync.isLoading
              ? const Center(child: CircularProgressIndicator())
              : _empty(petAsync.hasError ? l10n.healthLoadError : l10n.healthNoProfile))
          : async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) {
          if (e is DioException && e.response?.statusCode == 404) {
            return _empty(l10n.healthNoProfile);
          }
          return _empty(l10n.healthLoadError);
        },
        data: (items) {
          _handleFocusOnce(items);
          return _dataView(context, ref, l10n, items, readOnly: readOnly, petId: petId);
        },
      ),
    );
  }

  /// 0711 health-list：KATEGORI 分类网格 + SEMUA CATATAN 列表 + 底部整宽「Tambah Catatan」。
  Widget _dataView(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    List<HealthListItem> items, {
    bool readOnly = false,
    int? petId,
  }) {
    final filter = _filter;
    final shown = filter == null
        ? items
        : [for (final i in items) if (!i.isConsult && i.type == filter) i];
    final safety = _safety;
    return Column(
      children: [
        Expanded(
          child: ListView(
            // 需要定位时把缓存区拉大到「整页都构建出来」：`ListView` 默认懒 layout，
            // 首屏外的条目连 element 都没挂载 → 目标那条的 GlobalKey 拿不到 context，
            // `ensureVisible` 无从下手（表现为「点进来还是停在顶部」）。
            // 只在带 `?focus=` 时付这个构建成本；健康记录量级是几十条，代价可忽略。
            scrollCacheExtent: widget.focusRecordId != null
                ? const ScrollCacheExtent.pixels(10000)
                : null,
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            children: [
              // AC5.2：提醒带来的警示框在分类卡上方，随页面滚动、不可关闭。
              for (final s in safety) ...[
                _safetyBox(l10n, s),
                const SizedBox(height: 10),
              ],
              if (safety.isNotEmpty) const SizedBox(height: 2),
              _sectionLabel(l10n.healthCategorySection),
              const SizedBox(height: 8),
              _categoryGrid(context, ref, l10n, items, petId: petId),
              const SizedBox(height: 20),
              _listHeader(l10n, filter),
              const SizedBox(height: 8),
              if (shown.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Center(
                    child: Text(
                      // AC4.3：有筛选 → 筛选空态（哪怕宠物完全没有记录）；无筛选 → 现有空态。
                      filter == null ? l10n.healthListEmpty : l10n.healthFilterEmpty,
                      key: ValueKey(filter == null ? 'healthListEmpty' : 'healthFilterEmpty'),
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.4),
                    ),
                  ),
                )
              else
                for (final item in shown) ...[
                  _recordCard(context, ref, l10n, item),
                  const SizedBox(height: 10),
                ],
            ],
          ),
        ),
        _bottomAddBar(context, ref, l10n, readOnly: readOnly),
      ],
    );
  }

  Widget _sectionLabel(String text) => Padding(
    padding: const EdgeInsets.only(left: 2, top: 4),
    child: Text(
      text,
      style: const TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.5,
        color: AppColors.muted,
      ),
    ),
  );

  /// 列表标题（AC4.2）：无筛选「SEMUA CATATAN · TERBARU」；有筛选「{KATEGORI} · TERBARU」+ 右侧「Lihat semua」。
  Widget _listHeader(AppLocalizations l10n, String? filter) {
    if (filter == null) return _sectionLabel(l10n.healthAllSection);
    return Row(
      children: [
        Expanded(
          child: _sectionLabel(l10n.healthFilterTitle(_typeLabel(l10n, filter).toUpperCase())),
        ),
        TextButton(
          key: const ValueKey('healthFilterClear'),
          onPressed: () => setState(() => _filter = null),
          style: TextButton.styleFrom(
            foregroundColor: AppColors.mint,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            minimumSize: const Size(48, 36),
          ),
          child: Text(l10n.petRecommendSeeAll,
              style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }

  /// 提醒落页的警示框（AC5.2，样式同推送设置页状态卡：coralTint 底 + 白色圆形图标）。
  Widget _safetyBox(AppLocalizations l10n, String kind) {
    final text = kind == 'PREGNANT' ? l10n.healthSafetyPregnant : l10n.healthSafetyPermethrin;
    return Container(
      key: ValueKey('healthSafety_$kind'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.coralTint,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 32,
            height: 32,
            alignment: Alignment.center,
            decoration: const BoxDecoration(color: AppColors.card, shape: BoxShape.circle),
            child: const Icon(Icons.warning_amber_rounded, size: 18, color: AppColors.healthEventText),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(color: AppColors.healthEventText, fontSize: 13, height: 1.4),
            ),
          ),
        ],
      ),
    );
  }

  /// 6 类分类卡网格（app 六 type 一一对应；最近日期前端按 items 聚合，无则「Belum ada」）。
  /// V1.4.0 multi-pet Story 2.4：UI 稿 E3 的「{n} catatan」条数口径**未采用**（单宠用户不得有变化，code review #5，待确认）。
  Widget _categoryGrid(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    List<HealthListItem> items, {
    int? petId,
  }) {
    // 图标与主色取 FR-84 图标总表（`kHealthRecordIcons`，全项目一份）——本页曾是这套图标的
    // 事实源，V1.1.2 Story 2.2 把它抽到 domain 常量表，日历格子与 Diary 时间线胶囊共用同一份。
    final cats = <_HealthCat>[
      _HealthCat('VACCINE', l10n.healthTypeVaccine, false),
      _HealthCat('DEWORM', l10n.healthTypeDeworm, false),
      _HealthCat('NEUTER', l10n.healthTypeNeuter, false),
      _HealthCat('MENSTRUATION', l10n.healthTypeMenstruation, false),
      _HealthCat('CUSTOM', l10n.healthTypeCustom, false),
      _HealthCat('CONSULT', l10n.healthTypeConsult, true),
    ];
    return GridView.count(
      crossAxisCount: 3,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 10,
      crossAxisSpacing: 10,
      childAspectRatio: 0.82,
      children: [for (final c in cats) _categoryCard(context, l10n, c, items, petId: petId)],
    );
  }

  /// 点分类卡（AC4.1）：Konsultasi → 去发起问诊；其余 → 切换筛选（再点同一张 = 清除）。
  /// V1.5.0 起点卡**不再**直接弹添加表单（PRD FR-172），只读宠物也就不需要在这里拦。
  void _onCategoryTap(BuildContext context, _HealthCat c, List<HealthListItem> items, int? petId) {
    if (c.consult) {
      Analytics.capture('health_consult_card_tapped', {'pet_id': ?petId});
      context.push('/triage');
      return;
    }
    if (_filter == c.type) {
      setState(() => _filter = null); // 清除不报埋点（AC4.6）
      return;
    }
    setState(() => _filter = c.type);
    final count = items.where((i) => !i.isConsult && i.type == c.type).length;
    Analytics.capture('health_category_filtered', {
      'pet_id': ?petId,
      'category': c.type.toLowerCase(), // 枚举值小写蛇形（cloud-rules 埋点约定）
      'record_count': count,
    });
  }

  Widget _categoryCard(
    BuildContext context,
    AppLocalizations l10n,
    _HealthCat c,
    List<HealthListItem> items, {
    int? petId,
  }) {
    final selected = !c.consult && _filter == c.type;
    final String subtitle;
    if (c.consult) {
      // AC3.2：Konsultasi 卡副标题固定，不显示日期。
      subtitle = l10n.healthConsultCardSubtitle;
    } else {
      final latest = _latestDate(items, consult: false, type: c.type);
      subtitle = latest == null ? l10n.healthCategoryEmpty : formatDayMonthYear(context, latest);
    }
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('healthCat_${c.type}'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => _onCategoryTap(context, c, items, petId),
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            // 选中：2px mint 描边，白底不变（AC4.1）。
            border: selected
                ? Border.all(color: AppColors.mint, width: 2)
                : Border.all(color: AppColors.line),
          ),
          child: Stack(
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 12),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Container(
                      width: 44,
                      height: 44,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: c.color.withValues(alpha: 0.12),
                        shape: BoxShape.circle,
                      ),
                      child: Icon(c.icon, size: 22, color: c.color),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      c.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: selected ? AppColors.mint : AppColors.ink,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, color: AppColors.muted),
                    ),
                  ],
                ),
              ),
              if (c.consult)
                // AC3.2：常驻绿点（不读在线状态、不发请求），读屏标签复用「Selalu aktif · Jawaban otomatis」。
                Positioned(
                  top: 10,
                  right: 10,
                  child: Semantics(
                    label: l10n.triageAiAlwaysOn,
                    child: OnlinePulseDot(
                        key: const ValueKey('healthConsultPulse'),
                        pulsing: HealthListPage.debugConsultPulse),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  DateTime? _latestDate(List<HealthListItem> items, {required bool consult, required String type}) {
    DateTime? latest;
    for (final i in items) {
      final match = consult ? i.isConsult : (!i.isConsult && i.type == type);
      if (match && i.eventDate != null && (latest == null || i.eventDate!.isAfter(latest))) {
        latest = i.eventDate;
      }
    }
    return latest;
  }

  Widget _bottomAddBar(BuildContext context, WidgetRef ref, AppLocalizations l10n,
          {bool readOnly = false}) => SafeArea(
    top: false,
    child: Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
      color: AppColors.cream2,
      child: SizedBox(
        width: double.infinity,
        child: FilledButton.icon(
          key: const ValueKey('healthAddBottom'),
          // AC4.4：有筛选时预选该类型。
          onPressed: () => _openForm(context, ref, presetType: _filter),
          icon: Icon(readOnly ? Icons.lock_outline : Icons.add),
          label: Text(l10n.healthAddTitle),
        ),
      ),
    ),
  );

  Widget _empty(String text) => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.health_and_safety_outlined, size: 64, color: AppColors.mint500),
          const SizedBox(height: 16),
          Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.ink2, fontSize: 14, height: 1.4),
          ),
        ],
      ),
    ),
  );

  // V1.5.0 Story 3.1 · AC2.4：疫苗 / 驱虫卡下的「Lihat produk Obat & Vitamin」行（FR-110 品类跳转）已随 PRD §6.1 删除。

  Widget _recordCard(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, HealthListItem item) {
    final focused =
        widget.focusRecordId != null && !item.isConsult && item.id == widget.focusRecordId;
    // AC2.1：记录自身日期挪到右上角、与类型标签同行，格式同分类卡（「3 Des 2026」）。
    final dateStr = item.eventDate == null ? '' : formatDayMonthYear(context, item.eventDate!);
    final title = item.isConsult
        ? (item.symptomSummary ?? l10n.healthTypeConsult)
        : _recordTitle(l10n, item);
    // 问诊只读卡按 done 显示（AC2.3）。
    final status = item.isConsult ? HealthRecordStatus.done : item.recordStatus;
    final done = status == HealthRecordStatus.done;
    final pill = done ? null : _statusPill(context, l10n, item, status);
    return AnimatedContainer(
      key: focused ? _focusKey : null,
      duration: const Duration(milliseconds: 260),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        // 高亮用薄荷描边 + 浅底，不改卡片本体样式（避免看着像另一种类型的卡）。
        border: Border.all(
          color: focused && _highlighting ? AppColors.mint : Colors.transparent,
          width: 2,
        ),
      ),
      child: Material(
        color: focused && _highlighting ? AppColors.mintTint : AppColors.card,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          key: ValueKey('healthTile_${item.kind}_${item.id}'),
          borderRadius: BorderRadius.circular(14),
          // editable 区分可点：结构化可编辑；问诊只读不响应。
          onTap: item.editable ? () => _openForm(context, ref, existing: item) : null,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Opacity(
                  // done：图标淡化（AC2.3）。
                  opacity: done ? 0.55 : 1,
                  child: Container(
                    width: 40,
                    height: 40,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: item.isConsult ? AppColors.coralTint : AppColors.mintTint,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(item.isConsult ? '🏥' : '🐾', style: const TextStyle(fontSize: 18)),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // ⚠️ 行内 Text 都必须可收缩（code-review 2026-08-04）：只读徽章原先既不
                      // Flexible 也无 ellipsis，411dp 真机宽度下问诊条目会横向溢出 55px。
                      Row(
                        children: [
                          // 类型标签 + 只读徽章占满左侧剩余宽度；日期在最右（AC2.1）。
                          Expanded(
                            child: Row(
                              children: [
                                Flexible(
                                  child: Text(
                                    // Kontrol dokter 卡的类型行显示「Konsultasi」（PRD FR-161 Record card）。
                                    _typeLabel(l10n, item.type == 'VET_VISIT' ? 'CONSULT' : item.type),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: done ? AppColors.muted : AppColors.mint,
                                      fontSize: 12,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ),
                                if (item.isConsult) ...[
                                  const SizedBox(width: 6),
                                  Flexible(
                                    child: Text(
                                      l10n.healthReadOnlyBadge,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(color: AppColors.muted, fontSize: 11),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            dateStr,
                            key: ValueKey('healthTileDate_${item.kind}_${item.id}'),
                            style: const TextStyle(color: AppColors.muted, fontSize: 12),
                          ),
                        ],
                      ),
                      const SizedBox(height: 2),
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: done ? AppColors.ink2 : AppColors.ink,
                          fontSize: 15,
                          fontWeight: done ? FontWeight.w400 : FontWeight.w600,
                        ),
                      ),
                      if (pill != null) ...[
                        const SizedBox(height: 6),
                        pill,
                      ],
                      if (!item.isConsult && item.note != null && item.note!.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text(
                            item.note!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: AppColors.ink2, fontSize: 13),
                          ),
                        ),
                    ],
                  ),
                ),
                if (item.editable) ...[
                  const SizedBox(width: 4),
                  const Padding(
                    padding: EdgeInsets.only(top: 10),
                    child: Icon(Icons.chevron_right, color: AppColors.muted, size: 20),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 状态胶囊（AC2.3，架构 §7）：每种状态都有图标 + 文字，不只靠颜色。done 不出胶囊。
  Widget? _statusPill(
      BuildContext context, AppLocalizations l10n, HealthListItem item, HealthRecordStatus status) {
    final next = item.displayNextDate;
    final String text;
    final IconData icon;
    final Color bg;
    final Color fg;
    switch (status) {
      case HealthRecordStatus.overdue:
        text = l10n.healthStatusOverdue;
        icon = Icons.error_outline_rounded;
        bg = AppColors.coralTint;
        fg = AppColors.healthEventText;
      case HealthRecordStatus.dueSoon:
        if (next == null) return null;
        text = l10n.healthStatusNext(_nextDateLabel(context, next));
        icon = Icons.alarm_rounded;
        bg = AppColors.goldTint;
        fg = AppColors.tipsBadgeText;
      case HealthRecordStatus.upcoming:
        if (next == null) return null;
        text = l10n.healthStatusNext(_nextDateLabel(context, next));
        icon = Icons.calendar_today_rounded;
        bg = AppColors.violet100;
        fg = AppColors.mint700;
      case HealthRecordStatus.done:
        return null;
    }
    return Container(
      key: ValueKey('healthStatus_${status.name}_${item.id}'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(9999)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: fg),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: fg, fontSize: 11.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  /// 本年不写年、跨年写年（AC2.3）。
  static String _nextDateLabel(BuildContext context, DateTime d) =>
      d.year == DateTime.now().year ? formatDayMonth(context, d) : formatDayMonthYear(context, d);

  String _recordTitle(AppLocalizations l10n, HealthListItem item) {
    switch (item.type) {
      case 'CUSTOM':
        if (item.customName != null) return item.customName!;
      case 'VET_VISIT':
        return l10n.healthTypeVetVisit;
      case 'VACCINE':
        // AC2.2：`Vaksin · {预设标签或原文} · Dosis ke-{n}`。未知 / OTHER 预设 → 原文。
        final name = vaccinePresetLabel(l10n, item.vaccinePreset) ??
            ((item.vaccineName?.isNotEmpty ?? false) ? item.vaccineName : null);
        final parts = <String>[
          _typeLabel(l10n, item.type),
          ?name,
          if (item.dose != null) l10n.healthDoseLabel(item.dose!),
        ];
        return parts.join(' · ');
      case 'DEWORM':
        final kind = dewormTypeLabel(l10n, item.dewormType);
        return kind == null ? _typeLabel(l10n, item.type) : '${_typeLabel(l10n, item.type)} · $kind';
    }
    return _typeLabel(l10n, item.type);
  }

  String _typeLabel(AppLocalizations l10n, String type) => healthTypeLabel(l10n, type);

  Future<void> _openForm(
    BuildContext context,
    WidgetRef ref, {
    HealthListItem? existing,
    String? presetType,
    HealthRecordPrefill prefill = const HealthRecordPrefill(),
    String source = 'manual',
    String entry = 'health_page',
  }) async {
    // V1.4.0 multi-pet Story 2.4 · AC5.5：当前宠物只读 → 新增前端先拦（不发请求），弹 E1。
    // 打开**已有**记录不拦（AD-8：查看永不受限，code review #4）——拦在表单的保存 / 删除上。
    if (existing == null && !guardPetWritable(context, ref)) return;
    final outcome = await showModalBottomSheet<HealthFormOutcome>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => HealthRecordForm(
        existing: existing,
        presetType: presetType,
        prefill: prefill,
        petType: _currentPetType,
        entry: entry,
        source: source,
      ),
    );
    if (outcome != null) {
      // AC4.4：保存后筛选切到所存类型；Kontrol dokter 没有分类卡 → 清除。删除不动筛选（删光 → 筛选空态）。
      final savedType = outcome.savedType;
      if (savedType != null && mounted) {
        setState(() {
          _filter = _filter == null
              ? null
              : (HealthListPage.filterTypes.contains(savedType) ? savedType : null);
        });
      }
      ref.invalidate(healthListProvider);
      // bug 20260729-414：健康记录增删改后，Diary 侧四个常驻缓存 provider 必须同步失效，
      // 否则 /profile/health 是 shell 外路由，pop 回 Diary 不经切 Tab 刷新钩子，时间线/统计/
      // 日历仍是旧快照（口径同 pet_profile_edit_page._deleteProfile 的失效链）。
      ref.invalidate(timelineFirstPageProvider);
      ref.invalidate(archiveStatsProvider);
      ref.invalidate(petListProvider); // V1.4.0 multi-pet：「我的」页 diary 角标取宠物列表的 diaryCount
      ref.invalidate(calendarMonthProvider);
      ref.invalidate(dayDetailProvider);
    }
  }
}

/// 表单关闭结果：`savedType` = 新增 / 编辑后记录的类型；删除时为 null。
class HealthFormOutcome {
  const HealthFormOutcome.saved(String this.savedType);
  const HealthFormOutcome.deleted() : savedType = null;

  final String? savedType;
}

/// 健康记录类型名（列表 / 表单共用一份）：转调图标总表旁的 [healthRecordLabel]（全项目一份文案映射）。
String healthTypeLabel(AppLocalizations l10n, String type) => healthRecordLabel(l10n, type);

/// 疫苗预设标签（架构 §3.1）。OTHER / 空 / 不认识 → null（调用方显示 `vaccineName` 原文）。
String? vaccinePresetLabel(AppLocalizations l10n, String? preset) => switch (preset) {
      'F3' => l10n.healthVaccinePresetF3,
      'F4' => l10n.healthVaccinePresetF4,
      'RABIES' => l10n.healthVaccinePresetRabies,
      'FELV' => l10n.healthVaccinePresetFelv,
      'PUPPY_DP' => l10n.healthVaccinePresetPuppyDp,
      'COMBO_DHPPI_L' => l10n.healthVaccinePresetComboDhppiL,
      'LEPTO' => l10n.healthVaccinePresetLepto,
      'KENNEL_COUGH' => l10n.healthVaccinePresetKennelCough,
      'CANINE_INFLUENZA' => l10n.healthVaccinePresetCanineInfluenza,
      _ => null,
    };

/// 驱虫类型名（PRD FR-160）。空 / 不认识 → null（标题只写「Obat Cacing」）。
String? dewormTypeLabel(AppLocalizations l10n, String? type) => switch (type) {
      'INTERNAL' => l10n.healthDewormInternal,
      'EXTERNAL' => l10n.healthDewormExternal,
      'FLEA_WORM' => l10n.healthDewormFleaWorm,
      'HEARTWORM' => l10n.healthDewormHeartworm,
      _ => null,
    };

/// 健康记录分类卡的静态定义（0711 KATEGORI 网格：类型 + 标签 + 是否问诊类）。
/// 图标与主色不在此声明 —— 一律经 [healthRecordIconFor] 取 FR-84 图标总表，避免两份定义走歧。
class _HealthCat {
  const _HealthCat(this.type, this.label, this.consult);

  final String type;
  final String label;
  final bool consult;

  IconData get icon => healthRecordIconFor(type).icon;
  Color get color => healthRecordIconFor(type).color;
}

/// 本次保存健康记录是否存在可能解锁的里程碑候选（bug 20260729-405，纯函数 L0 可测）：
/// 对应类型的 M3（疫苗）/M4（驱虫）未完成，或 S1–S5 已齐且聚合 Lulus Pemula
/// （S16/S9，任一 pet_type 前缀）未完成——镜像后端 MilestoneAutoCompleteListener 判定前置。
bool mayUnlockHealthMilestone(String type, MilestoneList list) {
  final items = [for (final g in list.groups) ...g.items];
  bool uncompleted(String suffix) =>
      items.any((it) => !it.completed && it.code.endsWith('-$suffix'));
  bool completed(String suffix) => items.any((it) => it.completed && it.code.endsWith('-$suffix'));
  final direct = switch (type) {
    'VACCINE' => uncompleted('M3'),
    'DEWORM' => uncompleted('M4'),
    _ => false,
  };
  final sPrereqsDone = ['S1', 'S2', 'S3', 'S4', 'S5'].every(completed);
  final lulus = sPrereqsDone && (uncompleted('S16') || uncompleted('S9'));
  return direct || lulus;
}
