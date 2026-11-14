import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/network/problem_detail.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/keyboard_safe_area.dart';
import '../../../pet_slot/presentation/pet_readonly_block.dart';
import '../../data/current_pet_provider.dart';
import '../../data/health_record_repository.dart';
import '../../data/milestone_celebration_reporter.dart';
import '../../data/milestone_repository.dart';
import '../../domain/health_form_dirty.dart';
import '../../domain/health_list_item.dart';
import '../../domain/health_milestones.dart';
import '../../domain/milestone.dart';
import '../health_list_page.dart';
import 'milestone_celebration.dart';

/// 疫苗预设（架构增量 §3.1）：物种 → 下拉顺序。`OTHER` = 「Lainnya」+ 自由填写。
/// 只认 CAT / DOG，其余物种（含 ④ 物种线新增的）不出预设，只有单个「Nama vaksin」文本框。
const Map<String, List<String>> kVaccinePresetsBySpecies = {
  'CAT': ['F3', 'F4', 'RABIES', 'FELV', 'OTHER'],
  'DOG': ['PUPPY_DP', 'COMBO_DHPPI_L', 'RABIES', 'LEPTO', 'KENNEL_COUGH', 'CANINE_INFLUENZA', 'OTHER'],
};

/// 系列预设（显示「Dosis (opsional)」chip 行）；单剂预设与 OTHER 不显示。
const Set<String> kSeriesVaccinePresets = {'F3', 'F4', 'FELV', 'PUPPY_DP', 'COMBO_DHPPI_L', 'LEPTO'};

/// 驱虫类型下拉顺序（PRD FR-160）；`HEARTWORM` 只对 DOG 出现。
const List<String> kDewormTypes = ['INTERNAL', 'EXTERNAL', 'FLEA_WORM', 'HEARTWORM'];

/// 产品时长 chip（月，只 EXTERNAL）。
const List<int> kDewormDurations = [1, 3, 8];

/// 物种归一：只认 CAT / DOG，其余一律 OTHER（架构 §0）。
String healthSpeciesKind(String? petType) => switch (petType) {
      'CAT' => 'CAT',
      'DOG' => 'DOG',
      _ => 'OTHER',
    };

/// 埋点枚举值：小写蛇形（cloud-rules 埋点约定）。
String? _snake(String? code) => code?.toLowerCase();

/// 新增 / 编辑结构化健康记录表单（Story 7.2 起；V1.5.0 Story 3.2 重写）。
///
/// 字段顺序（UI 稿页头）：类型 → 子类型 → 剂次 / 时长 → 日期 → 下次日期 → 备注。
/// **App 不实现任何日期规则**：下次日期的预填、可选范围、是否显示 / 只读、提示全部来自
/// `POST …/health-records/preview`（规则只在后端 `profile.health` 一份）。
///
/// 关闭时返回 [HealthFormOutcome]（null = 未保存）。
class HealthRecordForm extends ConsumerStatefulWidget {
  const HealthRecordForm({
    super.key,
    this.existing,
    this.presetType,
    this.prefill = const HealthRecordPrefill(),
    this.petType,
    this.entry = 'health_page',
    this.source = 'manual',
  });

  final HealthListItem? existing;
  final String? presetType;

  /// 提醒 / 页面参数带来的预填（3.1 透传）。
  final HealthRecordPrefill prefill;

  /// 当前宠物物种原值（`PetListItem.petType`）；经 [healthSpeciesKind] 归一。
  final String? petType;

  /// `health_record_saved.entry`：`health_page` / `consult_page`。
  final String entry;

  /// `health_record_saved.source`：`manual` / `health_reminder` / `milestone`。
  final String source;

  @override
  ConsumerState<HealthRecordForm> createState() => _HealthRecordFormState();
}

class _HealthRecordFormState extends ConsumerState<HealthRecordForm> {
  late String _type;
  DateTime? _date;
  late final TextEditingController _name; // CUSTOM 名称 / 疫苗自由文本
  late final TextEditingController _note;

  String? _preset; // 疫苗预设 code（CAT / DOG 才有）
  int? _dose;
  String? _deworm;
  int _duration = 1;

  DateTime? _nextDate;
  bool _nextEdited = false; // 用户手动改过（含清空）→ 预填不再覆盖
  bool _nextCleared = false; // 用户清空过 → clearNextDate
  bool _nextPrefilled = false; // 当前值来自预填接口
  bool _nextEverPrefilled = false; // 本次打开出现过预填（埋点 next_date_prefilled）
  bool _nextTouched = false; // 本次打开里用户亲手选过 / 清过（埋点 next_date_edited）

  HealthRecordPreview? _preview;
  HealthRecordPreviewRequest? _lastRequest;
  Timer? _debounce;
  int _previewSeq = 0;

  bool _dewormError = false;
  bool _dateError = false;
  bool _nameError = false;

  bool _busy = false;
  late final String _initialSnapshot;
  late final HealthFormDirtyNotifier _dirty;

  /// 本次保存落到的宠物（V1.4.0 multi-pet）；里程碑庆祝检测按它取列表。
  int? _petId;

  String get _species => healthSpeciesKind(widget.petType);
  List<String> get _presetOptions => kVaccinePresetsBySpecies[_species] ?? const [];
  bool get _hasPresets => _presetOptions.isNotEmpty;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    final p = widget.prefill;
    _type = e?.type ?? widget.presetType ?? 'VACCINE';
    _date = e?.eventDate ?? (p.dateEmpty ? null : _today());
    _note = TextEditingController(text: e?.note ?? '');

    // 疫苗：匹配到当前物种的预设 → 选中；OTHER / 空 / 不认识 → Lainnya + 原文（PRD FR-168）。
    final rawPreset = e != null ? e.vaccinePreset : p.vaccinePreset;
    String nameText = e?.customName ?? p.customName ?? '';
    if (_hasPresets) {
      if (rawPreset != null && rawPreset != 'OTHER' && _presetOptions.contains(rawPreset)) {
        _preset = rawPreset;
      } else if (e != null && e.type == 'VACCINE') {
        _preset = 'OTHER';
        nameText = e.vaccineName ?? '';
      } else if (rawPreset == 'OTHER') {
        _preset = 'OTHER';
      }
    } else if (e != null && e.type == 'VACCINE') {
      nameText = e.vaccineName ?? '';
    }
    _name = TextEditingController(text: nameText);
    _dose = e?.dose ?? p.dose;
    final deworm = e?.dewormType ?? p.dewormType;
    _deworm = _dewormOptions.contains(deworm) ? deworm : null;
    _duration = e?.productDurationMonths ?? 1;
    // 编辑：已存的下次日期是用户的（或已确认的），打开时不被预填覆盖。
    _nextDate = e?.nextDate;
    _nextEdited = e?.nextDate != null;

    _name.addListener(_onTextChanged);
    _note.addListener(_onTextChanged);
    _initialSnapshot = _snapshot();
    _dirty = ref.read(healthFormDirtyProvider.notifier);
    // 打开即取一次预填（拿 nextDateAllowed / 提示；有预设时立刻给出建议日期，AC5.1）。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _requestPreview(immediate: true);
    });
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _name.dispose();
    _note.dispose();
    // 关闭抽屉（保存 / 取消 / 下滑）一律清掉未保存标记（AC6）。dispose 期间不能直接改 provider → 下一帧。
    final dirty = _dirty;
    Future.microtask(() => dirty.set(false));
    super.dispose();
  }

  static DateTime _today() {
    final n = DateTime.now();
    return DateTime(n.year, n.month, n.day);
  }

  List<String> get _dewormOptions =>
      [for (final t in kDewormTypes) if (t != 'HEARTWORM' || _species == 'DOG') t];

  /// 未保存判定用的字段快照（AC6）。
  String _snapshot() => [
        _type, _date?.toIso8601String(), _name.text, _note.text, _preset, _dose, _deworm,
        _duration, _nextDate?.toIso8601String(), _nextCleared,
      ].join('|');

  void _syncDirty() => _dirty.set(_snapshot() != _initialSnapshot);

  void _onTextChanged() {
    _syncDirty();
    // 疫苗自由文本参与关键词匹配（Rabies 提示等）→ 也算依赖字段。
    if (_type == 'VACCINE' && (_preset == 'OTHER' || !_hasPresets)) _requestPreview();
  }

  /// 依赖字段变了：改状态 → 同步脏标记 → 防抖调预填。
  void _update(VoidCallback change) {
    setState(change);
    _syncDirty();
    _requestPreview();
  }

  // ===================== 预填接口（AC2.3） =====================

  HealthRecordPreviewRequest _previewRequest() {
    final vaccine = _type == 'VACCINE';
    final deworm = _type == 'DEWORM';
    final freeName = vaccine && (_preset == 'OTHER' || !_hasPresets) ? _name.text.trim() : null;
    return HealthRecordPreviewRequest(
      recordId: widget.existing?.id,
      type: _type,
      vaccinePreset: vaccine ? _preset : null,
      vaccineName: freeName,
      dose: vaccine && kSeriesVaccinePresets.contains(_preset) ? _dose : null,
      dewormType: deworm ? _deworm : null,
      productDurationMonths: deworm && _deworm == 'EXTERNAL' ? _duration : null,
      eventDate: _date,
    );
  }

  void _requestPreview({bool immediate = false}) {
    _debounce?.cancel();
    if (immediate) {
      _runPreview();
    } else {
      _debounce = Timer(const Duration(milliseconds: 300), _runPreview);
    }
  }

  Future<void> _runPreview() async {
    final req = _previewRequest();
    if (req == _lastRequest) return;
    _lastRequest = req;
    final seq = ++_previewSeq;
    try {
      final petId = await readRequestPetId(ref);
      final res = await ref.read(healthRecordRepositoryProvider).preview(req, petId: petId);
      if (!mounted || seq != _previewSeq) return; // 过期响应丢弃
      setState(() {
        _preview = res;
        if (!_nextEdited) {
          // 没手动改过 → 用建议覆盖（null = 清空）。
          _nextDate = res.suggestedNextDate;
          _nextPrefilled = res.suggestedNextDate != null;
          if (_nextPrefilled) _nextEverPrefilled = true;
        }
      });
    } catch (_) {
      // 接口失败：不动字段、不报错（AC2.3）；允许同一请求稍后重试。
      if (seq == _previewSeq) _lastRequest = null;
    }
  }

  bool get _nextAllowed {
    if (_type == 'NEUTER') return false; // Sterilisasi 无下次日期字段（AC1.5）
    final p = _preview;
    if (p != null) return p.nextDateAllowed;
    // 预填还没回来：Berahi 是否有该字段取决于宠物（母猫 / 未绝育…），先不显示，避免闪一下又消失。
    return _type != 'MENSTRUATION';
  }

  bool get _nextLocked => _preview?.nextDateLocked ?? false;

  // ===================== 保存 / 删除 =====================

  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    // V1.4.0 multi-pet Story 2.4（code review #4）：只读宠物的已有记录可看，保存时才弹 E1。
    if (!guardPetWritable(context, ref)) return;
    final name = _name.text.trim();
    // AC4：字段级报错（保存按钮一直可点）。
    setState(() {
      _dewormError = _type == 'DEWORM' && _deworm == null;
      _dateError = _date == null;
      _nameError = _type == 'CUSTOM' && name.isEmpty;
    });
    if (_dewormError || _dateError || _nameError) return;

    setState(() => _busy = true);
    final vaccine = _type == 'VACCINE';
    final deworm = _type == 'DEWORM';
    final String? vaccineName;
    if (!vaccine) {
      vaccineName = null;
    } else if (_hasPresets && _preset != null && _preset != 'OTHER') {
      // AC1.6：写预设标签（架构 §3.1；ID / EN 同文，后端缺省时也会自己补同一串）。
      vaccineName = vaccinePresetLabel(l10n, _preset);
    } else {
      vaccineName = name;
    }
    final showNext = _nextAllowed && !_nextLocked;
    final draft = HealthRecordDraft(
      type: _type,
      eventDate: _date!,
      customName: _type == 'CUSTOM' ? name : null,
      vaccineName: vaccineName,
      note: _note.text.trim(),
      vaccinePreset: vaccine && _hasPresets ? _preset : null,
      dose: vaccine && kSeriesVaccinePresets.contains(_preset) ? _dose : null,
      dewormType: deworm ? _deworm : null,
      productDurationMonths: deworm && _deworm == 'EXTERNAL' ? _duration : null,
      nextDate: showNext ? _nextDate : null,
      clearNextDate: showNext && _nextDate == null && _nextCleared,
    );
    try {
      final repo = ref.read(healthRecordRepositoryProvider);
      // V1.4.0 multi-pet（Story 1.5）：记录落到当前宠物（列表取不到 → null = 后端默认宠物）。
      final petId = await readRequestPetId(ref);
      _petId = petId;
      if (widget.existing != null) {
        await repo.update(widget.existing!.id, draft, petId: petId);
        _reportSaved(widget.existing!.id, draft, isEdit: true);
        _dirty.set(false);
        if (!mounted) return;
        Navigator.of(context).pop(HealthFormOutcome.saved(_type));
        return;
      }
      // 新记录：保存前先取里程碑完成集（provider 命中缓存即秒回；冷取限时 2s——
      // 弱网/测试环境挂起时放弃庆祝检测，不拖慢保存主流程）。
      MilestoneList? before;
      try {
        if (petId != null) {
          before = await ref
              .read(milestoneListProvider(petId).future)
              .timeout(const Duration(seconds: 2));
        }
      } catch (_) {}
      final id = await repo.create(draft, petId: petId);
      _reportSaved(id, draft, isEdit: false);
      _dirty.set(false);
      // bug 20260729-405：解锁瞬间自动弹庆祝层（此前只有 SnackBar 提示，用户需自己去里程碑页才看到）。
      final unlocked = await _pollNewlyUnlocked(before);
      if (!mounted) return;
      if (unlocked != null) {
        await _celebrate(unlocked);
        if (!mounted) return;
      }
      // ⚠️ 无新解锁时**不提示任何里程碑文案**（2026-08-05 用户实机反馈）：原 F3 的垫底 toast
      // 「里程碑进度已更新 🏆」只看 type 是不是 VACCINE/DEWORM，不看是否真有变化 —— 第二针起
      // 每存一次都弹一次，带奖杯的措辞让人误以为又解锁了一个节点。真解锁走上面的庆祝层，
      // 这里静默即可（表单关闭 + 列表新增一条已是足够反馈）。**别再加回"顺带提一句"的提示。**
      Navigator.of(context).pop(HealthFormOutcome.saved(_type));
    } catch (e) {
      if (mounted) {
        setState(() => _busy = false);
        // V1.4.0 multi-pet Story 2.4 · AC5.6：后端兜底 409 `pet-read-only` → 统一弹 E1，不报保存失败。
        final pd = e is DioException ? ProblemDetail.fromDioException(e) : null;
        if (pd != null && handlePetReadOnly(context, ref, pd)) return;
        // AC4.2：驱虫未分型被后端拒（4xx 校验）→ 映射成字段报错，不直出 detail。
        final status = e is DioException ? e.response?.statusCode : null;
        if (deworm && status != null && status >= 400 && status < 500 && _deworm == null) {
          setState(() => _dewormError = true);
          return;
        }
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.healthSaveError)));
      }
    }
  }

  /// `health_record_saved`（PRD §4）：不报宠物名、备注、疫苗原文；枚举值小写蛇形。
  void _reportSaved(int? recordId, HealthRecordDraft d, {required bool isEdit}) {
    Analytics.capture('health_record_saved', {
      'record_id': ?recordId,
      'pet_id': ?_petId,
      'species': _species.toLowerCase(),
      'type': _type.toLowerCase(),
      'deworm_type': ?_snake(d.dewormType),
      'vaccine_preset': ?_snake(d.vaccinePreset),
      'dose': ?d.dose,
      'has_next_date': d.nextDate != null,
      'next_date_prefilled': _nextEverPrefilled,
      // 「用户改了预填」：出现过预填、且之后亲手选过 / 清过。
      'next_date_edited': _nextEverPrefilled && _nextTouched,
      'is_edit': isEdit,
      'entry': widget.entry,
      'source': widget.source,
    });
  }

  /// 保存后短轮询检测本次新解锁的里程碑（VACCINE→M3 / DEWORM→M4 / 聚合 Lulus Pemula）。
  ///
  /// 后端完成是 AFTER_COMMIT + @Async（MilestoneAutoCompleteListener）——create 返回瞬间大概率
  /// 尚未落库，须延时重拉。仅当存在解锁候选才轮询（否则每次保存都白等 ~2.5s）；
  /// [before] 拉取失败（null）时无法可靠 diff，宁可不弹也不误弹旧里程碑。
  Future<MilestoneItem?> _pollNewlyUnlocked(MilestoneList? before) async {
    final petId = _petId;
    if (petId == null || before == null || !mayUnlockHealthMilestone(_type, before)) return null;
    final beforeCodes = <String>{
      for (final g in before.groups)
        for (final it in g.items)
          if (it.completed) it.code,
    };
    for (final ms in const [500, 800, 1200]) {
      await Future<void>.delayed(Duration(milliseconds: ms));
      ref.invalidate(milestoneListProvider);
      try {
        final now = await ref
            .read(milestoneListProvider(petId).future)
            .timeout(const Duration(seconds: 2));
        // Story 5.2 · AC3：一次保存可能同时点亮多条（如首针疫苗 → M3 + 聚合「Lulus Pemula」）。
        // 收集**全部**新解锁，取**级别最高**的一条去庆祝（只弹一次）。
        final fresh = <MilestoneItem>[
          for (final g in now.groups)
            for (final it in g.items)
              if (it.completed && !beforeCodes.contains(it.code)) it,
        ];
        if (fresh.isNotEmpty) {
          return highestLevelMilestone(fresh);
        }
      } catch (_) {
        return null; // 拉取失败：放弃庆祝，不阻塞保存收尾。
      }
    }
    return null;
  }

  /// 弹 P-35 解锁庆祝（与发布「去发布」回填路径同 UX，见 publish_compose_page）。
  Future<void> _celebrate(MilestoneItem done) async {
    final petId = _petId;
    final listData = petId == null ? null : ref.read(milestoneListProvider(petId)).asData?.value;
    final petName = listData?.petName ?? '';
    final collection = listData == null
        ? const <MilestoneItem>[]
        : [for (final g in listData.groups) ...g.items];
    // V1.4.2 Story 3.2 · D-21：弹出前取好 container —— 庆祝关闭时本页可能已销毁。
    final container = ProviderScope.containerOf(context, listen: false);
    markMilestonesCelebrating(ref, [done.code]);
    await showMilestoneCelebration(
      context,
      done,
      petName: petName,
      path: MilestoneCelebrationPath.instant,
      collection: collection,
      petId: petId,
    );
    // 即时庆祝同样要回报，否则这条会在下次进里程碑列表页时被当成"未庆祝"再弹一遍（AD-A3.1）。
    reportMilestoneCelebratedIn(container, [done.code]);
  }

  Future<void> _delete() async {
    if (!guardPetWritable(context, ref)) return;
    setState(() => _busy = true);
    final e = widget.existing!;
    try {
      final petId = await readRequestPetId(ref);
      await ref.read(healthRecordRepositoryProvider).delete(e.id, petId: petId);
      // `source` = 原记录来源：列表接口不下发 record_origin / 创建时刻 → 本地无从得知，报 manual、
      // 不报 record_created_at（Completion Notes 记录）。
      Analytics.capture('health_record_deleted', {
        'record_id': e.id,
        'pet_id': ?petId,
        'type': e.type.toLowerCase(),
        'source': 'manual',
      });
      _dirty.set(false);
      if (mounted) Navigator.of(context).pop(const HealthFormOutcome.deleted());
    } catch (_) {
      if (mounted) {
        setState(() => _busy = false);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).healthSaveError)));
      }
    }
  }

  // ===================== UI =====================

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date ?? _today(),
      firstDate: DateTime(2000),
      lastDate: DateTime.now(), // 不可未来（AC2.1）
    );
    if (picked != null) {
      _update(() {
        _date = picked;
        _dateError = false;
      });
    }
  }

  Future<void> _pickNextDate() async {
    final p = _preview;
    final base = _date ?? _today();
    // 可选范围由接口给（eventDate +1 天 ~ +3 年）；接口没回来时只保证「晚于事件日」，其余交给后端校验。
    final first = p?.nextDateMin ?? base.add(const Duration(days: 1));
    final last = p?.nextDateMax ?? DateTime(2100);
    var initial = _nextDate ?? first;
    if (initial.isBefore(first)) initial = first;
    if (initial.isAfter(last)) initial = last;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: last,
    );
    if (picked != null) {
      setState(() {
        _nextDate = picked;
        _nextEdited = true;
        _nextTouched = true;
        _nextCleared = false;
        _nextPrefilled = false;
      });
      _syncDirty();
    }
  }

  void _clearNextDate() {
    setState(() {
      _nextDate = null;
      _nextEdited = true;
      _nextTouched = true;
      _nextCleared = true;
      _nextPrefilled = false;
    });
    _syncDirty();
  }

  void _onTypeChanged(String? v) {
    if (v == null || v == _type) return;
    _update(() {
      _type = v;
      _dewormError = false;
      _nameError = false;
      _preview = null; // 旧类型的 nextDateAllowed / 提示作废，等新结果
      if (!_nextEdited) _nextDate = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hints = _preview?.hints ?? const <String>[];
    return KeyboardInset(
      child: Padding(
        padding: const EdgeInsets.only(left: 20, right: 20, top: 18, bottom: 24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: AppColors.line,
                    borderRadius: BorderRadius.circular(9999),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Text(
                widget.existing == null ? l10n.healthAddTitle : l10n.healthEditTitle,
                style: const TextStyle(
                  color: AppColors.ink,
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 16),
              // ① 类型
              DropdownButtonFormField<String>(
                key: const ValueKey('healthTypeDropdown'),
            // 长选项（「Eksternal (kutu, caplak, tungau)」）在窄屏不溢出。
            isExpanded: true,
                initialValue: _type,
                decoration: InputDecoration(
                  labelText: l10n.healthFieldType,
                  border: const OutlineInputBorder(),
                ),
                items: [
                  for (final t in HealthListPage.recordTypes)
                    DropdownMenuItem(value: t, child: Text(healthTypeLabel(l10n, t))),
                ],
                onChanged: _onTypeChanged,
              ),
              // ② 子类型 + ③ 剂次 / 时长
              ..._subtypeFields(l10n, hints),
              const SizedBox(height: 12),
              // ④ 日期
              _dateField(l10n),
              // ⑤ 下次日期
              if (_nextAllowed) ...[
                const SizedBox(height: 12),
                _nextDateField(l10n),
              ],
              // 提示（PERMETHRIN 已在「Jenis obat」下方以警示框出现，这里不重复）
              for (final h in hints)
                if (h != 'PERMETHRIN' && _hintText(l10n, h) != null) ...[
                  const SizedBox(height: 10),
                  _hintBar(_hintText(l10n, h)!, key: ValueKey('healthHint_$h')),
                ],
              const SizedBox(height: 12),
              // ⑥ 备注
              TextField(
                controller: _note,
                maxLength: 100,
                decoration: InputDecoration(
                  labelText: l10n.healthFieldNote,
                  border: const OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton(
                key: const ValueKey('healthFormSave'),
                onPressed: _busy ? null : _save,
                child: _busy
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(l10n.healthSave),
              ),
              if (widget.existing != null) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: _busy ? null : _delete,
                  child: Text(l10n.healthDelete, style: const TextStyle(color: AppColors.popRed)),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _subtypeFields(AppLocalizations l10n, List<String> hints) {
    switch (_type) {
      case 'VACCINE':
        if (!_hasPresets) {
          // 其他物种：只有原来的单个「Nama vaksin」文本框。
          return [
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('healthVaccineName'),
              controller: _name,
              maxLength: 30,
              decoration: InputDecoration(
                labelText: l10n.healthFieldVaccineName,
                border: const OutlineInputBorder(),
              ),
            ),
          ];
        }
        return [
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            key: const ValueKey('healthVaccinePresetDropdown'),
            // 长选项（「Eksternal (kutu, caplak, tungau)」）在窄屏不溢出。
            isExpanded: true,
            initialValue: _preset,
            decoration: InputDecoration(
              labelText: l10n.healthFieldVaccineName,
              border: const OutlineInputBorder(),
            ),
            items: [
              for (final p in _presetOptions)
                DropdownMenuItem(
                  value: p,
                  child: Text(p == 'OTHER' ? l10n.healthVaccinePresetOther : vaccinePresetLabel(l10n, p)!),
                ),
            ],
            onChanged: (v) => _update(() {
              _preset = v;
              if (!kSeriesVaccinePresets.contains(v)) _dose = null;
            }),
          ),
          if (_preset == 'OTHER') ...[
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('healthVaccineName'),
              controller: _name,
              maxLength: 30,
              decoration: InputDecoration(
                labelText: l10n.healthVaccineOtherName,
                border: const OutlineInputBorder(),
              ),
            ),
          ],
          if (kSeriesVaccinePresets.contains(_preset)) ...[
            const SizedBox(height: 12),
            _chipRow(
              label: l10n.healthDoseOptional,
              keyPrefix: 'healthDose',
              values: const [1, 2, 3, 4, 5],
              selected: _dose,
              text: (n) => '$n',
              // 剂次可取消选择。
              onTap: (n) => _update(() => _dose = _dose == n ? null : n),
            ),
          ],
        ];
      case 'DEWORM':
        return [
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            key: const ValueKey('healthDewormDropdown'),
            // 长选项（「Eksternal (kutu, caplak, tungau)」）在窄屏不溢出。
            isExpanded: true,
            initialValue: _deworm,
            decoration: InputDecoration(
              labelText: l10n.healthDewormTypeField,
              border: const OutlineInputBorder(),
              errorText: _dewormError ? l10n.healthErrorDewormType : null,
            ),
            items: [
              for (final t in _dewormOptions)
                DropdownMenuItem(
                    value: t,
                    child: Text(dewormTypeLabel(l10n, t)!, overflow: TextOverflow.ellipsis)),
            ],
            onChanged: (v) => _update(() {
              _deworm = v;
              _dewormError = false;
            }),
          ),
          if (hints.contains('PERMETHRIN')) ...[
            const SizedBox(height: 10),
            _warningBox(l10n.healthSafetyPermethrin, key: const ValueKey('healthHint_PERMETHRIN')),
          ],
          if (_deworm == 'EXTERNAL') ...[
            const SizedBox(height: 12),
            _chipRow(
              label: l10n.healthDurationField,
              keyPrefix: 'healthDuration',
              values: kDewormDurations,
              selected: _duration,
              text: (n) => l10n.healthDurationMonths(n),
              onTap: (n) => _update(() => _duration = n),
            ),
          ],
        ];
      case 'CUSTOM':
        return [
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('healthCustomName'),
            controller: _name,
            maxLength: 20,
            onChanged: (_) {
              if (_nameError) setState(() => _nameError = false);
            },
            decoration: InputDecoration(
              labelText: l10n.healthFieldCustomName,
              border: const OutlineInputBorder(),
              errorText: _nameError ? l10n.healthCustomNameRequired : null,
            ),
          ),
        ];
      default:
        // Kontrol dokter / Berahi / Sterilisasi：无子类型。
        return const [];
    }
  }

  Widget _dateField(AppLocalizations l10n) {
    final d = _date;
    return InkWell(
      key: const ValueKey('healthDateField'),
      onTap: _pickDate,
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: l10n.healthFieldDate,
          border: const OutlineInputBorder(),
          errorText: _dateError ? l10n.healthErrorDate : null,
        ),
        child: Text(
          d == null ? l10n.petProfileBirthdayPick : formatDayMonthYear(context, d),
          style: TextStyle(color: d == null ? AppColors.muted : AppColors.ink),
        ),
      ),
    );
  }

  Widget _nextDateField(AppLocalizations l10n) {
    if (_nextLocked) {
      // 发情后续记录：只读显示本次发情的下次日期（FR-171）。
      final locked = _preview?.lockedNextDate;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InputDecorator(
            key: const ValueKey('healthNextDateLocked'),
            decoration: InputDecoration(
              labelText: l10n.healthNextDateField,
              border: const OutlineInputBorder(),
              enabled: false,
              filled: true,
              fillColor: AppColors.cream2,
              suffixIcon: const Icon(Icons.lock_outline, size: 18, color: AppColors.muted),
            ),
            child: Text(
              locked == null ? '' : formatDayMonthYear(context, locked),
              style: const TextStyle(color: AppColors.muted),
            ),
          ),
          const SizedBox(height: 6),
          Text(l10n.healthNextDateLockedHelper,
              style: const TextStyle(color: AppColors.muted, fontSize: 12)),
        ],
      );
    }
    final n = _nextDate;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          key: const ValueKey('healthNextDateField'),
          onTap: _pickNextDate,
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: l10n.healthNextDateField,
              border: const OutlineInputBorder(),
              suffixIcon: n == null
                  ? null
                  : IconButton(
                      key: const ValueKey('healthNextDateClear'),
                      icon: const Icon(Icons.close, size: 18),
                      tooltip: l10n.healthDelete,
                      onPressed: _clearNextDate,
                    ),
            ),
            child: Text(
              n == null ? '' : formatDayMonthYear(context, n),
              key: const ValueKey('healthNextDateText'),
              style: const TextStyle(color: AppColors.ink),
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(l10n.healthNextDateHelper, style: const TextStyle(color: AppColors.muted, fontSize: 12)),
      ],
    );
  }

  Widget _chipRow({
    required String label,
    required String keyPrefix,
    required List<int> values,
    required int? selected,
    required String Function(int) text,
    required void Function(int) onTap,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(color: AppColors.ink2, fontSize: 13)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final v in values)
              // 视觉 36 高 / 圆角 10；点击区 ≥48（padded tap target）。
              ChoiceChip(
                key: ValueKey('${keyPrefix}_$v'),
                label: Text(text(v)),
                selected: selected == v,
                showCheckmark: false,
                materialTapTargetSize: MaterialTapTargetSize.padded,
                visualDensity: VisualDensity.standard,
                labelPadding: const EdgeInsets.symmetric(horizontal: 6),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                selectedColor: AppColors.violet100,
                labelStyle: TextStyle(
                  color: selected == v ? AppColors.mint700 : AppColors.ink,
                  fontWeight: selected == v ? FontWeight.w600 : FontWeight.w400,
                ),
                onSelected: (_) => onTap(v),
              ),
          ],
        ),
      ],
    );
  }

  String? _hintText(AppLocalizations l10n, String code) => switch (code) {
        'VACCINE_PREGNANT' => l10n.healthHintVaccinePregnant,
        'RABIES_YEARLY' => l10n.healthHintRabiesYearly,
        'DEWORM_UNDER_8_WEEKS' => l10n.healthHintDewormUnder8Weeks,
        'DEWORM_PREGNANT' => l10n.healthHintDewormPregnant,
        'HEARTWORM_TEST' => l10n.healthHintHeartwormTest,
        'HEAT_NEUTERED' => l10n.healthHintHeatNeutered,
        _ => null, // 后端新增了 App 不认识的提示：忽略
      };

  /// 注意条：金色浅底 + ⓘ（与下次日期的普通灰色提示区分，AC3.1）。
  Widget _hintBar(String text, {Key? key}) => Container(
        key: key,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.goldTint,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.info_outline, size: 16, color: AppColors.tipsBadgeText),
            const SizedBox(width: 8),
            Expanded(
              child: Text(text,
                  style: const TextStyle(color: AppColors.tipsBadgeText, fontSize: 12.5, height: 1.35)),
            ),
          ],
        ),
      );

  /// 警示框（同 3.1 页顶警示框样式：coralTint 底 + 白色圆形图标）。
  Widget _warningBox(String text, {Key? key}) => Container(
        key: key,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppColors.coralTint,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 28,
              height: 28,
              alignment: Alignment.center,
              decoration: const BoxDecoration(color: AppColors.card, shape: BoxShape.circle),
              child: const Icon(Icons.warning_amber_rounded, size: 16, color: AppColors.healthEventText),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(text,
                  style: const TextStyle(color: AppColors.healthEventText, fontSize: 12.5, height: 1.4)),
            ),
          ],
        ),
      );
}
