import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/health_record_repository.dart';
import '../domain/health_list_item.dart';

/// 健康记录列表页 `p-health-list`（Story 7.2 · FR-45B/45C · UX-DR10）。结构化记录（可编辑）+ 问诊存档
/// （🏥 只读）混排，`editable` 区分可点。新增/编辑结构化记录；创建 VACCINE/DEWORM 联动里程碑第四路径。
class HealthListPage extends ConsumerWidget {
  const HealthListPage({super.key});

  static const List<String> recordTypes = [
    'VACCINE',
    'DEWORM',
    'MENSTRUATION',
    'NEUTER',
    'CUSTOM',
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(healthListProvider);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      // 0711：AppBar 右上「+」入口（与底部整宽按钮双入口，取代原 FAB）。
      appBar: AppBar(
        title: Text(l10n.healthListTitle),
        actions: [
          IconButton(
            key: const ValueKey('healthAddTop'),
            icon: const Icon(Icons.add, color: AppColors.ink),
            onPressed: () => _openForm(context, ref),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) {
          if (e is DioException && e.response?.statusCode == 404) {
            return _empty(l10n.healthNoProfile);
          }
          return _empty(l10n.healthLoadError);
        },
        data: (items) => _dataView(context, ref, l10n, items),
      ),
    );
  }

  /// 0711 health-list：KATEGORI 分类网格 + SEMUA CATATAN 列表 + 底部整宽「Tambah Catatan」。
  Widget _dataView(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, List<HealthListItem> items) {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            children: [
              _sectionLabel(l10n.healthCategorySection),
              const SizedBox(height: 8),
              _categoryGrid(context, ref, l10n, items),
              const SizedBox(height: 20),
              _sectionLabel(l10n.healthAllSection),
              const SizedBox(height: 8),
              if (items.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Center(
                    child: Text(l10n.healthListEmpty,
                        textAlign: TextAlign.center,
                        style:
                            const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.4)),
                  ),
                )
              else
                for (final item in items) ...[
                  _tile(context, ref, l10n, item),
                  const SizedBox(height: 10),
                ],
            ],
          ),
        ),
        _bottomAddBar(context, ref, l10n),
      ],
    );
  }

  Widget _sectionLabel(String text) => Padding(
        padding: const EdgeInsets.only(left: 2, top: 4),
        child: Text(text,
            style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.5,
                color: AppColors.muted)),
      );

  /// 6 类分类卡网格（app 六 type 一一对应；最近日期前端按 items 聚合，无则「Belum ada」）。
  Widget _categoryGrid(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, List<HealthListItem> items) {
    final cats = <_HealthCat>[
      _HealthCat('VACCINE', l10n.healthTypeVaccine, Icons.vaccines_outlined, AppColors.coral, false),
      _HealthCat('DEWORM', l10n.healthTypeDeworm, Icons.medication_outlined, AppColors.triageGreen, false),
      _HealthCat('NEUTER', l10n.healthTypeNeuter, Icons.healing_outlined, AppColors.mint, false),
      _HealthCat('MENSTRUATION', l10n.healthTypeMenstruation, Icons.water_drop_outlined,
          const Color(0xFF5B9BD5), false),
      _HealthCat('CUSTOM', l10n.healthTypeCustom, Icons.description_outlined, AppColors.muted, false),
      _HealthCat('CONSULT', l10n.healthTypeConsult, Icons.local_hospital_outlined, AppColors.coral, true),
    ];
    return GridView.count(
      crossAxisCount: 3,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: 10,
      crossAxisSpacing: 10,
      childAspectRatio: 0.82,
      children: [for (final c in cats) _categoryCard(context, ref, l10n, c, items)],
    );
  }

  Widget _categoryCard(BuildContext context, WidgetRef ref, AppLocalizations l10n, _HealthCat c,
      List<HealthListItem> items) {
    final latest = _latestDate(items, consult: c.consult, type: c.type);
    final dateStr = latest == null ? l10n.healthCategoryEmpty : _fmtDate(latest);
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('healthCat_${c.type}'),
        borderRadius: BorderRadius.circular(14),
        // FR-45C：点分类卡预选类型直接呼出添加弹层（问诊类不可手动添加）。
        onTap: c.consult ? null : () => _openForm(context, ref, presetType: c.type),
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: AppColors.line),
          ),
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
              Text(c.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 2),
              Text(dateStr, style: const TextStyle(fontSize: 11, color: AppColors.muted)),
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

  String _fmtDate(DateTime d) =>
      '${d.day} ${_monthId(d.month)} ${d.year}';

  static const List<String> _monthsId = [
    'Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'
  ];
  String _monthId(int m) => _monthsId[(m - 1).clamp(0, 11)];

  Widget _bottomAddBar(BuildContext context, WidgetRef ref, AppLocalizations l10n) => Container(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
        color: AppColors.cream2,
        child: SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            key: const ValueKey('healthAddBottom'),
            onPressed: () => _openForm(context, ref),
            icon: const Icon(Icons.add),
            label: Text(l10n.healthAddTitle),
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
              Text(text,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: AppColors.ink2, fontSize: 14, height: 1.4)),
            ],
          ),
        ),
      );

  Widget _tile(BuildContext context, WidgetRef ref, AppLocalizations l10n, HealthListItem item) {
    final dateStr = item.eventDate == null
        ? ''
        : '${item.eventDate!.year}-${item.eventDate!.month.toString().padLeft(2, '0')}'
            '-${item.eventDate!.day.toString().padLeft(2, '0')}';
    final title = item.isConsult
        ? (item.symptomSummary ?? l10n.healthTypeConsult)
        : _recordTitle(l10n, item);
    return Material(
      color: AppColors.card,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        key: ValueKey('healthTile_${item.kind}_${item.id}'),
        borderRadius: BorderRadius.circular(14),
        // editable 区分可点：结构化可编辑；问诊只读不响应。
        onTap: item.editable ? () => _openForm(context, ref, existing: item) : null,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: item.isConsult ? AppColors.coralTint : AppColors.mintTint,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(item.isConsult ? '🏥' : '🐾', style: const TextStyle(fontSize: 18)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(_typeLabel(l10n, item.type),
                              style: const TextStyle(
                                  color: AppColors.mint, fontSize: 12, fontWeight: FontWeight.w600)),
                        ),
                        if (item.isConsult) ...[
                          const SizedBox(width: 6),
                          Text(l10n.healthReadOnlyBadge,
                              style: const TextStyle(color: AppColors.muted, fontSize: 11)),
                        ],
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: AppColors.ink, fontSize: 15, fontWeight: FontWeight.w600)),
                    if (!item.isConsult && item.note != null && item.note!.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(item.note!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: AppColors.ink2, fontSize: 13)),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(dateStr, style: const TextStyle(color: AppColors.muted, fontSize: 12)),
              if (item.editable) const Icon(Icons.chevron_right, color: AppColors.muted, size: 20),
            ],
          ),
        ),
      ),
    );
  }

  String _recordTitle(AppLocalizations l10n, HealthListItem item) {
    if (item.type == 'CUSTOM' && item.customName != null) return item.customName!;
    if (item.type == 'VACCINE' && item.vaccineName != null && item.vaccineName!.isNotEmpty) {
      return '${_typeLabel(l10n, item.type)} · ${item.vaccineName}';
    }
    return _typeLabel(l10n, item.type);
  }

  String _typeLabel(AppLocalizations l10n, String type) {
    return switch (type) {
      'VACCINE' => l10n.healthTypeVaccine,
      'DEWORM' => l10n.healthTypeDeworm,
      'MENSTRUATION' => l10n.healthTypeMenstruation,
      'NEUTER' => l10n.healthTypeNeuter,
      'CONSULT' => l10n.healthTypeConsult,
      _ => l10n.healthTypeCustom,
    };
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref,
      {HealthListItem? existing, String? presetType}) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => _HealthRecordForm(existing: existing, presetType: presetType),
    );
    if (saved == true) {
      ref.invalidate(healthListProvider);
    }
  }
}

/// 新增/编辑结构化健康记录表单（Story 7.2）。type 下拉 + 日期（不可未来）+ 条件名 + 备注。返回 true=已保存。
class _HealthRecordForm extends ConsumerStatefulWidget {
  const _HealthRecordForm({this.existing, this.presetType});

  final HealthListItem? existing;
  final String? presetType;

  @override
  ConsumerState<_HealthRecordForm> createState() => _HealthRecordFormState();
}

class _HealthRecordFormState extends ConsumerState<_HealthRecordForm> {
  late String _type;
  late DateTime _date;
  late final TextEditingController _name;
  late final TextEditingController _note;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _type = e?.type ?? widget.presetType ?? 'VACCINE';
    _date = e?.eventDate ?? DateTime.now();
    _name = TextEditingController(text: e?.customName ?? e?.vaccineName ?? '');
    _note = TextEditingController(text: e?.note ?? '');
  }

  @override
  void dispose() {
    _name.dispose();
    _note.dispose();
    super.dispose();
  }

  bool get _needsName => _type == 'VACCINE' || _type == 'CUSTOM';

  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    final name = _name.text.trim();
    if (_type == 'CUSTOM' && name.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.healthCustomNameRequired)));
      return;
    }
    setState(() => _busy = true);
    final draft = HealthRecordDraft(
      type: _type,
      eventDate: _date,
      customName: _type == 'CUSTOM' ? name : null,
      vaccineName: _type == 'VACCINE' ? name : null,
      note: _note.text.trim(),
    );
    try {
      final repo = ref.read(healthRecordRepositoryProvider);
      if (widget.existing != null) {
        await repo.update(widget.existing!.id, draft);
      } else {
        await repo.create(draft);
      }
      if (!mounted) return;
      // 里程碑第四路径联动提示（F3，轻量非阻塞；真值后端异步完成）。
      if (widget.existing == null && (_type == 'VACCINE' || _type == 'DEWORM')) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(l10n.healthMilestoneHint)));
      }
      Navigator.of(context).pop(true);
    } catch (_) {
      if (mounted) {
        setState(() => _busy = false);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.healthSaveError)));
      }
    }
  }

  Future<void> _delete() async {
    setState(() => _busy = true);
    try {
      await ref.read(healthRecordRepositoryProvider).delete(widget.existing!.id);
      if (mounted) Navigator.of(context).pop(true);
    } catch (_) {
      if (mounted) {
        setState(() => _busy = false);
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).healthSaveError)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final dateStr = '${_date.year}-${_date.month.toString().padLeft(2, '0')}'
        '-${_date.day.toString().padLeft(2, '0')}';
    return Padding(
      padding: EdgeInsets.only(
          left: 20, right: 20, top: 18, bottom: MediaQuery.of(context).viewInsets.bottom + 24),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            Text(widget.existing == null ? l10n.healthAddTitle : l10n.healthEditTitle,
                style:
                    const TextStyle(color: AppColors.ink, fontSize: 17, fontWeight: FontWeight.w700)),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              key: const ValueKey('healthTypeDropdown'),
              initialValue: _type,
              decoration: InputDecoration(labelText: l10n.healthFieldType, border: const OutlineInputBorder()),
              items: [
                for (final t in HealthListPage.recordTypes)
                  DropdownMenuItem(value: t, child: Text(_label(l10n, t))),
              ],
              onChanged: (v) => setState(() => _type = v ?? _type),
            ),
            const SizedBox(height: 12),
            InkWell(
              onTap: () async {
                final picked = await showDatePicker(
                  context: context,
                  initialDate: _date,
                  firstDate: DateTime(2000),
                  lastDate: DateTime.now(), // 不可未来
                );
                if (picked != null) setState(() => _date = picked);
              },
              child: InputDecorator(
                decoration:
                    InputDecoration(labelText: l10n.healthFieldDate, border: const OutlineInputBorder()),
                child: Text(dateStr),
              ),
            ),
            if (_needsName) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _name,
                maxLength: _type == 'CUSTOM' ? 20 : 30,
                decoration: InputDecoration(
                  labelText: _type == 'CUSTOM' ? l10n.healthFieldCustomName : l10n.healthFieldVaccineName,
                  border: const OutlineInputBorder(),
                ),
              ),
            ],
            const SizedBox(height: 12),
            TextField(
              controller: _note,
              maxLength: 100,
              decoration: InputDecoration(labelText: l10n.healthFieldNote, border: const OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _busy ? null : _save,
              child: _busy
                  ? const SizedBox(
                      width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
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
    );
  }

  static String _label(AppLocalizations l10n, String type) => switch (type) {
        'VACCINE' => l10n.healthTypeVaccine,
        'DEWORM' => l10n.healthTypeDeworm,
        'MENSTRUATION' => l10n.healthTypeMenstruation,
        'NEUTER' => l10n.healthTypeNeuter,
        _ => l10n.healthTypeCustom,
      };
}

/// 健康记录分类卡的静态定义（0711 KATEGORI 网格：类型 + 标签 + 图标 + 主色 + 是否问诊类）。
class _HealthCat {
  const _HealthCat(this.type, this.label, this.icon, this.color, this.consult);

  final String type;
  final String label;
  final IconData icon;
  final Color color;
  final bool consult;
}
