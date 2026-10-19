import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../data/growth_frame_controller.dart';
import '../domain/growth_badge.dart';
import '../domain/growth_models.dart';

/// 等级页「Bingkai avatar」一排（V1.4.0 ① Story 3.3 · UI 稿 C1 ~ C5）：Tanpa bingkai / Dasar / Lanjutan / Animasi。
///
/// - 选中 = **当前生效**的框（自动生效的也算；生效为无框 → 「Tanpa bingkai」）。
/// - 未解锁：预览半透明 + 「🔒 Lv{n}」（`n` 来自后端四档的 `unlockLevel`，即等级表里该段位首级）；点了只 toast，不发请求。
/// - 点已解锁的其它卡：该卡转圈、其余卡暂不可点 → `PUT /me/growth/frame`；成功用响应刷新、toast、上报；失败勾留原卡 + toast。
/// - 点当前卡无反应。不另设保存按钮。
class FramePickerRow extends ConsumerStatefulWidget {
  const FramePickerRow({
    super.key,
    required this.frames,
    required this.onChanged,
    this.avatarUrl,
    this.nickname = '',
  });

  final GrowthFrames frames;
  final ValueChanged<GrowthFrames> onChanged;
  final String? avatarUrl;
  final String nickname;

  static const List<String> order = ['NONE', 'BASIC', 'UPGRADED', 'ANIMATED'];

  @override
  ConsumerState<FramePickerRow> createState() => _FramePickerRowState();
}

class _FramePickerRowState extends ConsumerState<FramePickerRow> {
  String? _pending;

  String get _selected {
    final e = widget.frames.effective;
    return (e == null || !FramePickerRow.order.contains(e)) ? 'NONE' : e;
  }

  GrowthFrameOption? _option(String code) => widget.frames.options.where((o) => o.frame == code).firstOrNull;

  bool _unlocked(String code) => code == 'NONE' || (_option(code)?.unlocked ?? false);

  Future<void> _tap(String code) async {
    if (_pending != null || code == _selected) return;
    final l10n = AppLocalizations.of(context);
    if (!_unlocked(code)) {
      showAppToast(context, l10n.growthLevelUnlocksAt('${_option(code)?.unlockLevel ?? ''}'));
      return;
    }
    final overlay = Overlay.of(context, rootOverlay: true);
    setState(() => _pending = code);
    try {
      final frames = await ref.read(growthFrameControllerProvider).choose(code);
      if (!mounted) return;
      widget.onChanged(frames);
      Analytics.capture('avatar_frame_changed', {'frame_id': code.toLowerCase()});
      showAppToastOnOverlay(overlay, l10n.growthFrameChanged);
    } catch (_) {
      showAppToastOnOverlay(overlay, l10n.growthFrameChangeFailed);
    } finally {
      if (mounted) setState(() => _pending = null);
    }
  }

  String _label(AppLocalizations l10n, String code) => switch (code) {
        'BASIC' => l10n.growthFrameBasic,
        'UPGRADED' => l10n.growthFrameAdvanced,
        'ANIMATED' => l10n.growthFrameAnimated,
        _ => l10n.growthFrameNone,
      };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Row(
      key: const ValueKey('growthFramePicker'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (i, code) in FramePickerRow.order.indexed) ...[
          if (i > 0) const SizedBox(width: 8),
          Expanded(
            child: _FrameCard(
              code: code,
              label: _label(l10n, code),
              selected: code == _selected,
              unlocked: _unlocked(code),
              unlockLevel: _option(code)?.unlockLevel,
              pending: _pending == code,
              // 切换中其余卡暂不可点。
              enabled: _pending == null,
              avatarUrl: widget.avatarUrl,
              nickname: widget.nickname,
              onTap: () => _tap(code),
            ),
          ),
        ],
      ],
    );
  }
}

class _FrameCard extends StatelessWidget {
  const _FrameCard({
    required this.code,
    required this.label,
    required this.selected,
    required this.unlocked,
    required this.unlockLevel,
    required this.pending,
    required this.enabled,
    required this.avatarUrl,
    required this.nickname,
    required this.onTap,
  });

  final String code;
  final String label;
  final bool selected;
  final bool unlocked;
  final int? unlockLevel;
  final bool pending;
  final bool enabled;
  final String? avatarUrl;
  final String nickname;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textColor = !unlocked
        ? AppColors.muted
        : selected
            ? AppColors.ink
            : AppColors.ink2;
    return GestureDetector(
      key: ValueKey('growthFrameCard_$code'),
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? onTap : null,
      child: Container(
        padding: const EdgeInsets.fromLTRB(4, 10, 4, 8),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint2 : AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line2, width: 1.5),
        ),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 44,
                  height: 44,
                  child: Center(
                    child: pending
                        ? const SizedBox(
                            key: ValueKey('growthFrameSpinner'),
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.5,
                              color: AppColors.mint,
                              backgroundColor: AppColors.growthFrameSpinTrack,
                            ),
                          )
                        : Opacity(opacity: unlocked ? 1 : 0.4, child: _preview()),
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11,
                    color: textColor,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                  ),
                ),
                if (!unlocked && unlockLevel != null) ...[
                  const SizedBox(height: 2),
                  Row(
                    key: ValueKey('growthFrameLock_$code'),
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.lock_rounded, size: 11, color: AppColors.muted),
                      const SizedBox(width: 2),
                      Text(
                        l10n.growthLevelShort('$unlockLevel'),
                        style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: AppColors.muted),
                      ),
                    ],
                  ),
                ],
              ],
            ),
            if (selected)
              Positioned(
                top: -5,
                right: 1,
                child: Container(
                  key: ValueKey('growthFrameCheck_$code'),
                  width: 16,
                  height: 16,
                  decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
                  child: const Icon(Icons.check_rounded, size: 12, color: AppColors.onAccent),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _preview() {
    final frame = GrowthFrame.fromCode(code);
    if (frame == null) {
      return Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: AppColors.line, width: 1.5)),
        child: const Icon(Icons.block_rounded, size: 20, color: AppColors.muted),
      );
    }
    // 预览只看框：等级 / 段位不显示（showLevelBadge: false），这里给占位值。
    return InitialAvatar(
      avatarUrl: avatarUrl,
      nickname: nickname,
      radius: 18,
      growth: GrowthBadge(level: 1, tier: GrowthTier.newbie, frame: frame),
      showLevelBadge: false,
    );
  }
}

/// 生效框代码 → 等级卡头像用的 [GrowthBadge]。
GrowthBadge? growthBadgeFor(GrowthSummary s, String? effectiveFrame) {
  final tier = s.tier;
  if (tier == null) return null;
  return GrowthBadge(level: s.level, tier: tier, frame: GrowthFrame.fromCode(effectiveFrame));
}
