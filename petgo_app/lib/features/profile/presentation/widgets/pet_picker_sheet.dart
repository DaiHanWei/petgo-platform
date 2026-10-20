import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/pet_list_repository.dart';
import '../../domain/pet_age.dart';
import 'pet_species_avatar.dart';

/// 选宠物底部抽屉（V1.4.0 multi-pet Story 3.3 · AC5.2 · UI 稿 G1 / G2 `.sheet .crow`）。
///
/// **单选 / 多选两种模式**：打卡 G1 用多选；4.1 问诊 H1 / 4.2 KTP I1 复用单选（点行即返回）。
/// 不为它们预做业务逻辑：调用方给列表（只给可选的那些，如只 ACTIVE）、标题 / 说明、
/// 哪些行不可选（[disabledPetIds]）及其副标题覆盖（[disabledSubtitle]）。
///
/// 返回所选宠物 id（**按 [pets] 的顺序** = 创建顺序，不按点选先后）；关闭抽屉 → null。
///
/// V1.4.0 multi-pet Story 4.1（H1 / H4）补三个可选参数（默认值 = 原行为，3.3 调用点零影响）：
/// - [confirmLabel]：单选模式下给了它 → 「点行只选中、吸底按钮确认」（UI 稿 H1 `.rcard` + 「Lanjut」），
///   选中行 `mintTint2` 底 + 描边 + `check_circle`，未选 `radio_button_unchecked`；
/// - [initialSelectedId]：上述模式的默认选中（问诊默认 Diary 当前宠物）；
/// - [lockedPetIds] / [lockedSubtitle]：**可选**但带锁的行（只读宠物：可问不可存档），头像角标锁 + 副标题覆盖。
Future<List<int>?> showPetPickerSheet(
  BuildContext context, {
  required List<PetListItem> pets,
  required String title,
  String? body,
  bool multiSelect = true,
  Set<int> disabledPetIds = const {},
  String? disabledSubtitle,
  String Function(int count)? submitLabel,
  String? confirmLabel,
  int? initialSelectedId,
  Set<int> lockedPetIds = const {},
  String? lockedSubtitle,
}) {
  return showModalBottomSheet<List<int>>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.card,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => PetPickerSheet(
      pets: pets,
      title: title,
      body: body,
      multiSelect: multiSelect,
      disabledPetIds: disabledPetIds,
      disabledSubtitle: disabledSubtitle,
      submitLabel: submitLabel,
      confirmLabel: confirmLabel,
      initialSelectedId: initialSelectedId,
      lockedPetIds: lockedPetIds,
      lockedSubtitle: lockedSubtitle,
    ),
  );
}

class PetPickerSheet extends StatefulWidget {
  const PetPickerSheet({
    super.key,
    required this.pets,
    required this.title,
    this.body,
    this.multiSelect = true,
    this.disabledPetIds = const {},
    this.disabledSubtitle,
    this.submitLabel,
    this.confirmLabel,
    this.initialSelectedId,
    this.lockedPetIds = const {},
    this.lockedSubtitle,
  });

  final List<PetListItem> pets;
  final String title;
  final String? body;

  /// true = 勾选多只 + 底部主按钮确认；false = 点一行即返回该只。
  final bool multiSelect;

  /// 不可选的行（打卡：今天已在此打过卡的宠物）：底 `#FAFAFB`、名字与头像 0.55 透明、尾部 `block`、不可点。
  final Set<int> disabledPetIds;

  /// 不可选行的副标题（覆盖「物种 · 年龄」）；null = 照常显示物种 · 年龄。
  final String? disabledSubtitle;

  /// 多选主按钮文案（入参 = 已选只数）；null = 系统本地化的「OK」。
  final String Function(int count)? submitLabel;

  /// 单选 + 吸底确认（Story 4.1 H1 / H4「Lanjut」）；null = 原单选行为（点行即返回）。多选模式忽略。
  final String? confirmLabel;

  /// 单选确认模式的默认选中（不在 [pets] 里 / 不可选 → 不预选）。
  final int? initialSelectedId;

  /// 可选但带锁的行（Story 4.1：只读宠物可问不可存档）：头像角标锁、副标题用 [lockedSubtitle]。
  final Set<int> lockedPetIds;

  /// 带锁行的副标题；null = 照常显示物种 · 年龄。
  final String? lockedSubtitle;

  @override
  State<PetPickerSheet> createState() => _PetPickerSheetState();
}

class _PetPickerSheetState extends State<PetPickerSheet> {
  /// 默认**全不选**（AC5.2）；单选确认模式按 [PetPickerSheet.initialSelectedId] 预选。
  final Set<int> _selected = <int>{};

  bool get _singleConfirm => !widget.multiSelect && widget.confirmLabel != null;

  @override
  void initState() {
    super.initState();
    final initial = widget.initialSelectedId;
    if (_singleConfirm &&
        initial != null &&
        !widget.disabledPetIds.contains(initial) &&
        widget.pets.any((p) => p.id == initial)) {
      _selected.add(initial);
    }
  }

  void _tap(int id) {
    if (_singleConfirm) {
      setState(() {
        _selected
          ..clear()
          ..add(id);
      });
      return;
    }
    if (!widget.multiSelect) {
      Navigator.of(context).pop(<int>[id]);
      return;
    }
    setState(() {
      if (!_selected.remove(id)) _selected.add(id);
    });
  }

  void _submit() {
    if (_selected.isEmpty) return;
    Navigator.of(context).pop([
      for (final p in widget.pets)
        if (_selected.contains(p.id)) p.id,
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final count = _selected.length;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            Text(widget.title,
                key: const ValueKey('petPickerTitle'),
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            if (widget.body != null) ...[
              const SizedBox(height: 6),
              Text(widget.body!,
                  style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            ],
            const SizedBox(height: 14),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  children: [
                    for (final p in widget.pets) _row(l10n, p),
                  ],
                ),
              ),
            ),
            if (_singleConfirm) ...[
              const SizedBox(height: 8),
              FilledButton(
                key: const ValueKey('petPickerConfirm'),
                onPressed: count > 0 ? () => Navigator.of(context).pop(_selected.toList()) : null,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  disabledBackgroundColor: AppColors.line2,
                  disabledForegroundColor: AppColors.muted,
                ),
                child: Text(widget.confirmLabel!),
              ),
            ],
            if (widget.multiSelect) ...[
              const SizedBox(height: 8),
              FilledButton(
                key: const ValueKey('petPickerSubmit'),
                onPressed: count > 0 ? _submit : null,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  disabledBackgroundColor: AppColors.line2,
                  disabledForegroundColor: AppColors.muted,
                ),
                child: Text(widget.submitLabel?.call(count) ?? MaterialLocalizations.of(context).okButtonLabel),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _row(AppLocalizations l10n, PetListItem p) {
    final disabled = widget.disabledPetIds.contains(p.id);
    final selected = _selected.contains(p.id);
    final meta = [
      ?petSpeciesLabel(l10n, p.petType),
      ?formatPetAge(l10n, p.birthday)?.replaceAll(' ', ' '),
    ].join(' · ');
    final locked = !disabled && widget.lockedPetIds.contains(p.id);
    final subtitle = disabled && widget.disabledSubtitle != null
        ? widget.disabledSubtitle!
        : locked && widget.lockedSubtitle != null
            ? widget.lockedSubtitle!
            : meta;
    final Widget trailing;
    if (disabled) {
      trailing = const Icon(Icons.block, size: 20, color: AppColors.muted);
    } else if (_singleConfirm) {
      trailing = Icon(selected ? Icons.check_circle : Icons.radio_button_unchecked,
          key: ValueKey('petPickerRadio-${p.id}'),
          size: 22,
          color: selected ? AppColors.mint : AppColors.muted);
    } else if (widget.multiSelect) {
      trailing = Icon(selected ? Icons.check_box : Icons.check_box_outline_blank,
          key: ValueKey('petPickerCheck-${p.id}'),
          color: selected ? AppColors.mint : AppColors.ink2);
    } else {
      trailing = const Icon(Icons.chevron_right_rounded, color: AppColors.ink2);
    }
    return PetCardRow(
      key: ValueKey('petPickerRow-${p.id}'),
      avatarUrl: p.avatarUrl,
      petType: p.petType,
      name: p.name,
      subtitle: subtitle,
      trailing: trailing,
      selected: selected,
      disabled: disabled,
      locked: locked,
      onTap: disabled ? null : () => _tap(p.id),
    );
  }
}

/// 统一卡片行（UI 稿 `.sheet .crow`）：内边距 11 × 12、1.5 描边、圆角 14、行距 8、行高 ≥ 60；
/// 头像 40、名字 14.5 / w600、副标题 12 `ink2`、尾部 [trailing]。
///
/// 选宠物抽屉与详情页「关联宠物」抽屉（AC4.7，无勾选框、尾部 `chevron_right`）共用。
class PetCardRow extends StatelessWidget {
  const PetCardRow({
    super.key,
    required this.avatarUrl,
    this.petType,
    required this.name,
    required this.subtitle,
    required this.trailing,
    this.selected = false,
    this.disabled = false,
    this.locked = false,
    this.onTap,
  });

  final String? avatarUrl;

  /// 无头像时按物种出 emoji 占位（bug 593）；null → 中性爪印。
  final String? petType;
  final String name;
  final String subtitle;
  final Widget trailing;
  final bool selected;

  /// 不可选：底 `#FAFAFB`、名字与头像 0.55 透明（副标题与尾部图标不透明，CSS `.sheet .crow.dis`）。
  final bool disabled;

  /// 头像右下角小锁（Story 4.1 H1：只读宠物行，仍可点）。
  final bool locked;
  final VoidCallback? onTap;

  static const Color disabledBg = Color(0xFFFAFAFB);

  @override
  Widget build(BuildContext context) {
    Widget avatar = PetSpeciesAvatar(avatarUrl: avatarUrl, petType: petType, size: 40);
    Widget nameText = Text(name,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: AppColors.ink));
    if (locked) {
      avatar = Stack(
        clipBehavior: Clip.none,
        children: [
          avatar,
          Positioned(
            right: -2,
            bottom: -2,
            child: Container(
              key: const ValueKey('petCardRowLock'),
              width: 16,
              height: 16,
              decoration: BoxDecoration(
                color: AppColors.mint,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.card, width: 1.5),
              ),
              child: const Icon(Icons.lock_rounded, size: 9, color: AppColors.onAccent),
            ),
          ),
        ],
      );
    }
    if (disabled) {
      avatar = Opacity(opacity: 0.55, child: avatar);
      nameText = Opacity(opacity: 0.55, child: nameText);
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: disabled ? disabledBg : (selected ? AppColors.mintTint2 : AppColors.card),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: selected ? AppColors.mint : AppColors.line2, width: 1.5),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 60),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              child: Row(
                children: [
                  avatar,
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        nameText,
                        if (subtitle.isNotEmpty) ...[
                          const SizedBox(height: 2),
                          Text(subtitle,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  trailing,
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
