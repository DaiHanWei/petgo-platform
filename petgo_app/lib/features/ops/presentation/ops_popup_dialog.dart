import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 弹窗关闭结果（AC5）。系统返回键关闭 → `null`，调用方按 [close] 处理。
enum OpsPopupOutcome { tap, close }

/// 运营弹窗卡片（V1.3.4 Story 5.4 · AC2 · UI 稿 D1 / D2）。
///
/// - D1：图 + 标题 / 正文 + 按钮；D2：只有图 + 按钮（[title] / [body] 都为空）。
/// - ✕ 压在图片右上角（UI 决策 2026-10-09）：视觉圆 32、热区 44。
/// - 文案全部来自后端，不新增 l10n key；✕ 的无障碍标签复用 `commonClose`。
class OpsPopupDialog extends StatefulWidget {
  const OpsPopupDialog({
    super.key,
    required this.image,
    this.title,
    this.body,
    required this.buttonText,
    required this.imageTappable,
    required this.onShown,
  });

  /// 与预加载同一个 provider（AC4.4），展示时直接命中缓存。
  final ImageProvider image;
  final String? title;
  final String? body;
  final String buttonText;

  /// 目标有效才可点图片；无效时点图片无反应（AC5.3）。
  final bool imageTappable;

  /// 首帧后回调一次（写本地频次 / 曝光上报 / `ops_popup_shown`，AC3.4）。
  final VoidCallback onShown;

  static const double maxCardWidth = 288;
  static const double sideGutter = 24;
  static const double radius = 24;
  static const double closeVisual = 32;
  static const double closeHitBox = 44;
  static const double closeInset = 10;
  static const double buttonHeight = 46;

  /// UI 稿 `.op-ov` `rgba(20,16,30,.6)`。
  static const Color barrierColor = Color(0x9914101E);

  /// ✕ 视觉圆底色：深色半透明（压在图片上）/ 浅色（浅色头部，存量汇总弹窗 E1）。
  static const Color closeFillDark = Color(0x59000000);
  static const Color closeFillLight = Color(0xB3FFFFFF);

  @override
  State<OpsPopupDialog> createState() => _OpsPopupDialogState();
}

class _OpsPopupDialogState extends State<OpsPopupDialog> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onShown();
    });
  }

  void _pop(OpsPopupOutcome outcome) => Navigator.of(context).pop(outcome);

  @override
  Widget build(BuildContext context) {
    final hasText = widget.title != null || widget.body != null;
    return OpsPopupCardShell(
      header: _imageArea(context),
      text: hasText ? _textArea() : null,
      button: _button(),
    );
  }

  /// 4:5 图 + 右上角 ✕（视觉圆 32 距上 / 右各 10，热区 44 以圆心为中心 → 距上 / 右各 4）。
  Widget _imageArea(BuildContext context) {
    const hitInset = OpsPopupDialog.closeInset -
        (OpsPopupDialog.closeHitBox - OpsPopupDialog.closeVisual) / 2;
    return Stack(
      children: [
        GestureDetector(
          key: const Key('opsPopupImageTap'),
          onTap: widget.imageTappable ? () => _pop(OpsPopupOutcome.tap) : null,
          child: AspectRatio(
            aspectRatio: 4 / 5,
            child: Container(
              // 1px 8% 黑内描边。
              foregroundDecoration: BoxDecoration(
                border: Border.all(color: const Color(0x14000000), width: 1),
              ),
              child: Image(
                key: const Key('opsPopupImage'),
                image: widget.image,
                fit: BoxFit.cover,
                gaplessPlayback: true,
              ),
            ),
          ),
        ),
        Positioned(
          top: hitInset,
          right: hitInset,
          child: OpsPopupCloseButton(
            tapKey: const Key('opsPopupClose'),
            onTap: () => _pop(OpsPopupOutcome.close),
          ),
        ),
      ],
    );
  }

  /// 标题（2 行）+ 正文（4 行），超出卡片高度上限时在这里滚动。
  Widget _textArea() {
    final title = widget.title;
    final body = widget.body;
    return SingleChildScrollView(
      key: const Key('opsPopupTextScroll'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (title != null)
            Text(
              title,
              key: const Key('opsPopupTitle'),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
          if (title != null && body != null) const SizedBox(height: 6),
          if (body != null)
            Text(
              body,
              key: const Key('opsPopupBody'),
              maxLines: 4,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5),
            ),
        ],
      ),
    );
  }

  Widget _button() {
    return SizedBox(
      width: double.infinity,
      height: OpsPopupDialog.buttonHeight,
      child: Material(
        color: AppColors.mint,
        borderRadius: const BorderRadius.all(Radius.circular(12)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: const Key('opsPopupButton'),
          onTap: () => _pop(OpsPopupOutcome.tap),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(
                widget.buttonText,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 14.5, fontWeight: FontWeight.w700, color: Colors.white),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 运营弹窗卡片外壳（V1.4.0 ① Story 2.3 抽出，存量汇总弹窗 E1 复用）：288 宽 / 圆角 24 / 阴影 / 高度上限，
/// 上方 [header]、下方文案区（[text] 可空，超高时在内部滚动）+ [button]。外观与 Key（`opsPopupCard`）与抽出前逐字一致。
class OpsPopupCardShell extends StatelessWidget {
  const OpsPopupCardShell({super.key, required this.header, this.text, required this.button});

  final Widget header;
  final Widget? text;
  final Widget button;

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.sizeOf(context).width;
    final width = math.min(
        OpsPopupDialog.maxCardWidth, screenWidth - OpsPopupDialog.sideGutter * 2);
    final hasText = text != null;
    const radius = BorderRadius.all(Radius.circular(OpsPopupDialog.radius));

    // 高度上限 = 可用（DialogRoute 已套安全区）高度 − 48：小屏 + 大字号时文案区内部滚动，卡片不溢出。
    return LayoutBuilder(builder: (context, constraints) {
      final maxHeight = math.max(0.0, constraints.maxHeight - OpsPopupDialog.sideGutter * 2);
      return Center(
        child: Material(
          type: MaterialType.transparency,
          child: Container(
            key: const Key('opsPopupCard'),
            width: width,
            constraints: BoxConstraints(maxHeight: maxHeight),
            decoration: const BoxDecoration(
              color: Colors.white,
              borderRadius: radius,
              boxShadow: [
                // UI 稿 `0 20px 48px rgba(20,16,30,.28)`。
                BoxShadow(color: Color(0x4714101E), blurRadius: 48, offset: Offset(0, 20)),
              ],
            ),
            child: ClipRRect(
              borderRadius: radius,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  header,
                  Flexible(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (hasText) Flexible(child: text!),
                          // 按钮前间距：有文案 14，D2 形态（只有图 + 按钮）0。
                          if (hasText) const SizedBox(height: 14),
                          button,
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    });
  }
}

/// 弹窗右上角 ✕（视觉圆 32、热区 44）。[onLight]：浅色头部用浅底深色叉（E1），默认深色半透明底白叉（运营弹窗）。
class OpsPopupCloseButton extends StatelessWidget {
  const OpsPopupCloseButton({super.key, required this.onTap, this.tapKey, this.onLight = false});

  final VoidCallback onTap;
  final Key? tapKey;
  final bool onLight;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Semantics(
      label: l10n.commonClose,
      button: true,
      excludeSemantics: true,
      // excludeSemantics 会丢掉子树的点击动作，这里显式补上（TalkBack / VoiceOver 双击可关）。
      onTap: onTap,
      child: GestureDetector(
        key: tapKey,
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: SizedBox(
          width: OpsPopupDialog.closeHitBox,
          height: OpsPopupDialog.closeHitBox,
          child: Center(
            child: Container(
              width: OpsPopupDialog.closeVisual,
              height: OpsPopupDialog.closeVisual,
              decoration: BoxDecoration(
                color: onLight ? OpsPopupDialog.closeFillLight : OpsPopupDialog.closeFillDark,
                shape: BoxShape.circle,
              ),
              alignment: Alignment.center,
              child: Icon(Icons.close_rounded, size: 16, color: onLight ? AppColors.ink : Colors.white),
            ),
          ),
        ),
      ),
    );
  }
}

/// 挂在根 Navigator 上弹出（AC2.1）：点遮罩不关；系统返回键关闭 → `null`。
Future<OpsPopupOutcome?> showOpsPopupDialog(BuildContext rootContext, OpsPopupDialog dialog) {
  return showDialog<OpsPopupOutcome>(
    context: rootContext,
    useRootNavigator: true,
    barrierDismissible: false,
    barrierColor: OpsPopupDialog.barrierColor,
    builder: (_) => dialog,
  );
}
