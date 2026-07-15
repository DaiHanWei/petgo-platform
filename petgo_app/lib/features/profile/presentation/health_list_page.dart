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
      appBar: AppBar(title: Text(l10n.healthListTitle)),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('healthAddFab'),
        onPressed: () => _openForm(context, ref),
        backgroundColor: AppColors.mint,
        icon: const Icon(Icons.add, color: Colors.white),
        label: Text(l10n.healthAddTitle, style: const TextStyle(color: Colors.white)),
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) {
          if (e is DioException && e.response?.statusCode == 404) {
            return _empty(l10n.healthNoProfile);
          }
          return _empty(l10n.healthLoadError);
        },
        data: (items) => items.isEmpty
            ? _empty(l10n.healthListEmpty)
            : ListView.separated(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
                itemCount: items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 10),
                itemBuilder: (_, i) => _tile(context, ref, l10n, items[i]),
              ),
      ),
    );
  }

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

  Future<void> _openForm(BuildContext context, WidgetRef ref, {HealthListItem? existing}) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => _HealthRecordForm(existing: existing),
    );
    if (saved == true) {
      ref.invalidate(healthListProvider);
    }
  }
}

/// 新增/编辑结构化健康记录表单（Story 7.2）。type 下拉 + 日期（不可未来）+ 条件名 + 备注。返回 true=已保存。
class _HealthRecordForm extends ConsumerStatefulWidget {
  const _HealthRecordForm({this.existing});

  final HealthListItem? existing;

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
    _type = e?.type ?? 'VACCINE';
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
