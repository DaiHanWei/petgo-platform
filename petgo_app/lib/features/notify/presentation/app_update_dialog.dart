import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/app_version_check.dart';
import 'app_update_icons.dart';

/// App 更新弹窗（V1.3.4 Story 3.2 重做为品牌卡片，UX-DR6 / UI 稿 C2）。App 内提示，
/// <b>不走推送、不需推送权限</b>。
///
/// - 强制（[UpdateDecision.forced]）：**不可跳过** —— 无「Nanti」、`barrierDismissible: false`、
///   `PopScope(canPop: false)`；点「Perbarui sekarang」跳商店后弹窗**不关闭**（PRD AC4）。
/// - 推荐（[UpdateDecision.recommended]，Story 3.4 调用）：主按钮下方有「Nanti」；
///   点「Nanti」先关窗再回调 [onLater]（由调用方记冷却 / 报埋点）；点遮罩 / 返回键可关，
///   但**不**回调 [onLater]（不记冷却、不报埋点）。
/// - [onGoStore] 为 null（本平台商店链接为空）→ **不渲染**主按钮（PRD AC6）。
class AppUpdateDialog extends StatelessWidget {
  const AppUpdateDialog({
    super.key,
    required this.decision,
    required this.version,
    this.onGoStore,
    this.onLater,
  });

  final UpdateDecision decision;

  /// 本平台最新版本号（版本标签「Versi {version}」）。
  final String version;
  final VoidCallback? onGoStore;
  final VoidCallback? onLater;

  /// 遮罩 rgba(20,16,30,.5)。
  static const Color barrierColor = Color(0x8014101E);

  /// 展示更新弹窗。强制态 barrierDismissible=false + PopScope 拦截返回。
  static Future<void> show(
    BuildContext context, {
    required UpdateDecision decision,
    required String version,
    VoidCallback? onGoStore,
    VoidCallback? onLater,
  }) {
    final forced = decision == UpdateDecision.forced;
    return showDialog<void>(
      context: context,
      barrierDismissible: !forced,
      barrierColor: barrierColor,
      builder: (_) => AppUpdateDialog(
        decision: decision,
        version: version,
        onGoStore: onGoStore,
        onLater: onLater,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final forced = decision == UpdateDecision.forced;
    final goStore = onGoStore;

    final card = Container(
      key: ValueKey(forced ? 'appUpdateForced' : 'appUpdateRecommended'),
      width: double.infinity, // 由外层 maxWidth 312 收口；无按钮时也保持卡片宽度
      padding: const EdgeInsets.fromLTRB(20, 28, 20, 18),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(24),
        boxShadow: const [
          BoxShadow(color: Color(0x4014101E), offset: Offset(0, 20), blurRadius: 48),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Container(
            key: const ValueKey('appUpdateIcon'),
            width: 64,
            height: 64,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(20),
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.mint500, AppColors.mint],
              ),
              boxShadow: const [
                BoxShadow(color: Color(0x4D845EC9), offset: Offset(0, 8), blurRadius: 20),
              ],
            ),
            child: SvgPicture.string(
              kUpdatePhoneSvgDialog,
              width: 30,
              height: 30,
              colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
            ),
          ),
          const SizedBox(height: 16),
          Text(
            forced ? l10n.updateForceTitle : l10n.updateRecommendTitle,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w700,
              color: AppColors.ink,
              height: 1.3,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 8, 4, 0),
            child: Text(
              forced ? l10n.updateForceBody : l10n.updateRecommendBody,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13.5, color: AppColors.ink2, height: 1.5),
            ),
          ),
          const SizedBox(height: 12),
          Container(
            key: const ValueKey('appUpdateVersionLabel'),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: AppColors.mintTint,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              l10n.updateVersionLabel(version),
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.mint700,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ),
          if (goStore != null) ...[
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              height: 46,
              child: FilledButton(
                key: const ValueKey('appUpdateGoStore'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  textStyle: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700),
                ),
                onPressed: () {
                  // 强制态：跳商店后弹窗**不关闭**（用户从商店回来仍被拦住）。
                  // 推荐态：先关窗再跳。
                  if (!forced) Navigator.of(context).pop();
                  goStore();
                },
                child: Text(l10n.updateGoStore),
              ),
            ),
          ],
          if (!forced) ...[
            const SizedBox(height: 4),
            SizedBox(
              width: double.infinity,
              height: 44,
              child: TextButton(
                key: const ValueKey('appUpdateLater'),
                style: TextButton.styleFrom(
                  foregroundColor: AppColors.ink2,
                  textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                ),
                onPressed: () {
                  Navigator.of(context).pop();
                  onLater?.call();
                },
                child: Text(l10n.updateLater),
              ),
            ),
          ],
        ],
      ),
    );

    final dialog = Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 24),
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 312),
        child: card,
      ),
    );
    // 强制态拦截系统返回手势/物理键，确保不可跳过。
    return PopScope(canPop: !forced, child: dialog);
  }
}
