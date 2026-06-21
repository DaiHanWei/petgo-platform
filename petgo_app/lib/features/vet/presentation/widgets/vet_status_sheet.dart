import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/vet_online_status.dart';

/// V-st 状态切换底抽屉（原型 vet-status-popup）：三态选项（Online/Sibuk/Offline）+「Simpan Status」。
/// 由工作台顶栏在线药丸点击弹出。选定后经 [vetAvailabilityProvider] 持久化（二元映射，Sibuk 为前端占位）。
Future<void> showVetStatusSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (_) => const _VetStatusSheet(),
  );
}

class _VetStatusSheet extends ConsumerStatefulWidget {
  const _VetStatusSheet();

  @override
  ConsumerState<_VetStatusSheet> createState() => _VetStatusSheetState();
}

class _VetStatusSheetState extends ConsumerState<_VetStatusSheet> {
  late VetAvailability _selected = ref.read(vetAvailabilityProvider);
  bool _saving = false;

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    await ref.read(vetAvailabilityProvider.notifier).select(_selected);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('vetStatusSheet'),
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      padding: const EdgeInsets.fromLTRB(22, 12, 22, 36),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 抽屉把手
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 18),
                decoration: BoxDecoration(
                    color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            Text(l10n.vetStatusSheetTitle,
                style: const TextStyle(
                    fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 4),
            Text(l10n.vetStatusSheetSubtitle,
                style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
            const SizedBox(height: 20),
            _StatusOption(
              emoji: '🟢',
              iconBg: AppColors.vetPrimary,
              icon: Icons.check_circle_outline,
              accent: AppColors.vetPrimary,
              tint: AppColors.vetSurface,
              title: l10n.vetOnlineShort,
              desc: l10n.vetStatusOnlineDesc,
              selected: _selected == VetAvailability.online,
              optionKey: 'vetStatusOptionOnline',
              onTap: () => setState(() => _selected = VetAvailability.online),
            ),
            const SizedBox(height: 10),
            _StatusOption(
              emoji: '🟡',
              iconBg: AppColors.goldTint,
              fallbackEmoji: '⏳',
              accent: AppColors.triageYellow,
              tint: AppColors.surface,
              title: l10n.vetBusyShort,
              desc: l10n.vetStatusBusyDesc,
              selected: _selected == VetAvailability.busy,
              optionKey: 'vetStatusOptionBusy',
              onTap: () => setState(() => _selected = VetAvailability.busy),
            ),
            const SizedBox(height: 10),
            _StatusOption(
              emoji: '⚫',
              iconBg: AppColors.base,
              fallbackEmoji: '💤',
              accent: AppColors.textTertiary,
              tint: AppColors.surface,
              title: l10n.vetOfflineShort,
              desc: l10n.vetStatusOfflineDesc,
              selected: _selected == VetAvailability.offline,
              optionKey: 'vetStatusOptionOffline',
              onTap: () => setState(() => _selected = VetAvailability.offline),
            ),
            const SizedBox(height: 22),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('vetStatusSave'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.vetTopBar,
                  foregroundColor: AppColors.vetOnAccent,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: _saving ? null : _save,
                child: Text(l10n.vetStatusSaveButton,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 状态选项卡：左 42px 圆形图标 + 标题/描述 + 右单选指示；选中态加色边框 + 浅底。
class _StatusOption extends StatelessWidget {
  const _StatusOption({
    required this.emoji,
    required this.iconBg,
    required this.accent,
    required this.tint,
    required this.title,
    required this.desc,
    required this.selected,
    required this.optionKey,
    required this.onTap,
    this.icon,
    this.fallbackEmoji,
  });

  final String emoji; // 标题前缀色点 emoji（🟢/🟡/⚫）
  final Color iconBg; // 左圆底色
  final Color accent; // 选中边框/单选色
  final Color tint; // 选中浅底
  final String title;
  final String desc;
  final bool selected;
  final String optionKey;
  final VoidCallback onTap;
  final IconData? icon; // 左圆内图标（online 用对勾）
  final String? fallbackEmoji; // 左圆内 emoji（busy ⏳ / offline 💤）

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: ValueKey(optionKey),
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? tint : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected ? accent : AppColors.line,
            width: selected ? 2 : 1.5,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 42,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(color: iconBg, shape: BoxShape.circle),
              child: icon != null
                  ? Icon(icon, color: AppColors.vetOnAccent, size: 22)
                  : Text(fallbackEmoji ?? '', style: const TextStyle(fontSize: 22)),
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('$emoji $title',
                      style: const TextStyle(
                          fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  const SizedBox(height: 2),
                  Text(desc,
                      style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                ],
              ),
            ),
            const SizedBox(width: 8),
            // 单选指示
            Container(
              width: 20,
              height: 20,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: selected ? accent : Colors.transparent,
                border: Border.all(color: selected ? accent : AppColors.line, width: selected ? 2 : 1.5),
              ),
              child: selected
                  ? const Icon(Icons.check, size: 12, color: AppColors.vetOnAccent)
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}
