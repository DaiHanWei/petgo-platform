import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/triage/domain/triage_navigation.dart';
import '../../l10n/app_localizations.dart';

/// 红色半屏强提醒 overlay（Story 4.5，FR-3/UX-DR7）。生命安全支柱最直接用户面。
///
/// 🔒 不可协商：① 0–5s 锁定（双按钮禁用 + 倒计时，背景/拖拽/返回键均不可关闭）；
/// ② 解锁后「去导航」系统确认→系统地图、「稍后处理」二次确认→关闭；
/// ③ 全程**零兽医 / 零变现引流**；④ alertdialog + assertive 打断式播报、⚠️+大字非颜色单一。
class RedAlertOverlay extends ConsumerStatefulWidget {
  const RedAlertOverlay({
    super.key,
    required this.title,
    required this.onAcknowledge,
    this.lockSeconds = 5,
  });

  /// 已本地化主标题（含宠物名，如「请立即带 Momo 就医」）。
  final String title;

  /// 「稍后处理」二次确认通过后回调（由宿主关闭半屏）。
  final VoidCallback onAcknowledge;

  /// 锁定秒数（默认 5；测试可注入更短）。
  final int lockSeconds;

  @override
  ConsumerState<RedAlertOverlay> createState() => _RedAlertOverlayState();
}

class _RedAlertOverlayState extends ConsumerState<RedAlertOverlay> {
  late int _remaining = widget.lockSeconds;
  Timer? _timer;

  bool get _locked => _remaining > 0;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (_remaining <= 0) {
        t.cancel();
        return;
      }
      setState(() => _remaining--);
      if (_remaining <= 0) t.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _navigate() => confirmAndNavigate(context, ref);

  Future<void> _later() async {
    final l10n = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(l10n.triageRedRiskConfirmTitle),
        actions: <Widget>[
          TextButton(
              onPressed: () => Navigator.pop(ctx, false), child: Text(l10n.commonCancel)),
          FilledButton(
            key: const ValueKey('triageRedRiskConfirm'),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(l10n.triageRedRiskConfirmYes),
          ),
        ],
      ),
    );
    if (ok == true) widget.onAcknowledge();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 🔒 5s 内拦截系统返回键（锁定不可绕过）；解锁后亦只经「稍后处理」二次确认关闭，故恒不可 pop。
    return PopScope(
      canPop: false,
      child: Semantics(
        container: true,
        liveRegion: true, // assertive 打断式播报
        label: '${widget.title}. ${l10n.triageRedSubtext}',
        child: Container(
          width: double.infinity,
          color: AppColors.triageRed, // #C97A7A
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, AppSpacing.xxl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              // ⚠️ 大白图标（非颜色单一：icon + 大字 + 红底三重）
              const Icon(Icons.warning_amber_rounded,
                  key: ValueKey('triageRedIcon'), color: Colors.white, size: 64),
              const SizedBox(height: AppSpacing.lg),
              Text(widget.title,
                  style: AppTypography.display.copyWith(color: Colors.white),
                  textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.triageRedSubtext,
                  style: AppTypography.body.copyWith(color: Colors.white),
                  textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.xl),
              if (_locked)
                Text(
                  l10n.triageRedCountdown(_remaining),
                  key: const ValueKey('triageRedCountdown'),
                  style: AppTypography.caption.copyWith(color: Colors.white),
                ),
              const SizedBox(height: AppSpacing.sm),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('triageRedNavigate'),
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.white,
                    foregroundColor: AppColors.triageRed,
                    minimumSize: const Size.fromHeight(48), // ≥44pt 触摸目标
                  ),
                  onPressed: _locked ? null : _navigate,
                  child: Text(l10n.triageRedNavigate),
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  key: const ValueKey('triageRedLater'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.white,
                    side: const BorderSide(color: Colors.white),
                    minimumSize: const Size.fromHeight(48),
                  ),
                  onPressed: _locked ? null : _later,
                  child: Text(l10n.triageRedLater),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
