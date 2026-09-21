import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'app_toast.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';
import '../config/support_contact.dart';
import '../config/support_contact_repository.dart';

/// 🔴 V1.3.0 Story 3-1：号码不再是本文件的私有常量。
///
/// 权威值由后端 `GET /api/v1/support/contact` 下发（运营改后台配置即生效，不需发版）；
/// 读不到时回退 `kFallbackSupportContact` —— 那是全 App **唯一**的号码字面量，
/// Story 3-3 的 WhatsApp 深链读的也是它。**别在这里再放一份副本**，
/// 放了就又变回「换号要改两处」。

// 客服抽屉视觉 token（对齐原型 cs-contact-sheet.html）。
const Color _kCsWaTint = Color(0xFFE7F8EF); // WhatsApp 图标绿底
const Color _kCsWaGreen = Color(0xFF1FB877); // WhatsApp 图标绿
const Color _kCsMuted = Color(0xFFEFEDF3); // 复制键/取消键灰底（原型 --color-bg-muted）

/// 客服底部抽屉 · 对齐原型 cs-contact-sheet.html：
/// 每条「色块圆角图标 + 值 + 标签·时效 + 独立灰色复制键」，行间分隔线，底部灰色填充取消键。
///
/// 共享组件：「我的」页帮助入口与兽医登录页「成为合作伙伴/联系我们」入口共用。
Future<void> showCustomerServiceSheet(BuildContext context) {
  final l10n = AppLocalizations.of(context);
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
    ),
    // 内容套滚动容器：非 isScrollControlled 的 modal sheet 高度上限=屏高×9/16，
    // 英文/大字号下副标题折两行会把固定 Column 顶破上限（720 宽真机溢出 23px、Tutup 被截）。
    // 可滚动后内容不超高时视觉零变化，超高时自动可滚，任何屏幕/字号都安全。
    // 🔴 用 Consumer 就地取 provider，**不改 showCustomerServiceSheet 的签名** ——
    //    改成要 WidgetRef 就得动三个调用点（「我」页 + 兽医登录页两处），
    //    而那三处与本 story 无关。
    builder: (ctx) => Consumer(builder: (ctx, ref, _) {
      // provider 内部已吞掉所有异常、永不进 error 态；这里再用 maybeWhen 兜一次
      // loading 那一帧 —— 弹窗**永不出现空值或占位符**（AC7）。
      final contact = ref.watch(supportContactProvider).maybeWhen(
            data: (c) => c,
            orElse: () => kFallbackSupportContact,
          );
      return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 20),
                decoration: BoxDecoration(
                  color: AppColors.line,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ),
            Text(
              l10n.csSheetTitle,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            // WhatsApp：绿底 + 真 WhatsApp logo，号码可复制。
            _CsContactRow(
              glyph: SvgPicture.asset(
                'assets/brand/whatsapp.svg',
                width: 22,
                height: 22,
                colorFilter: const ColorFilter.mode(_kCsWaGreen, BlendMode.srcIn),
              ),
              iconBg: _kCsWaTint,
              value: contact.whatsappNumber,
              sub: '${l10n.csWhatsappLabel} · ${l10n.csWhatsappNote}',
              copyText: contact.whatsappNumber,
            ),
            const Divider(height: 1, thickness: 1, color: AppColors.line2),
            // 邮箱：紫底紫标，地址可复制。
            _CsContactRow(
              glyph: const Icon(Icons.mail_outline, size: 22, color: AppColors.mint),
              iconBg: AppColors.mintTint2,
              value: contact.email,
              sub: '${l10n.csEmailLabel} · ${l10n.csEmailNote}',
              copyText: contact.email,
            ),
            const Divider(height: 1, thickness: 1, color: AppColors.line2),
            // 站内工单行（0718 新增，原两个按钮合并成一行）：→ 工单列表（提交+追踪 hub）。
            _CsContactRow(
              glyph: const Icon(Icons.chat_bubble_outline, size: 22, color: AppColors.mint),
              iconBg: AppColors.mintTint2,
              value: l10n.csTicketRowTitle,
              sub: l10n.csTicketRowSub,
              onTap: () {
                Navigator.of(ctx).pop();
                context.push('/me/support-tickets');
              },
            ),
            const SizedBox(height: 16),
            // 关闭：灰色填充（非描边），对齐原型 .cancel-btn，文案「Tutup」。
            FilledButton(
              key: const ValueKey('csCancel'),
              onPressed: () => Navigator.of(ctx).pop(),
              style: FilledButton.styleFrom(
                backgroundColor: _kCsMuted,
                foregroundColor: AppColors.textSecondary,
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 15),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(18),
                ),
              ),
              child: Text(
                l10n.csClose,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
      );
    }),
  );
}

/// 客服联系条（原型 .contact-row）：色块圆角图标 + 值 + 「标签 · 时效」副行 + 右侧独立灰色复制键。
class _CsContactRow extends StatelessWidget {
  const _CsContactRow({
    required this.glyph,
    required this.iconBg,
    required this.value,
    required this.sub,
    this.copyText,
    this.onTap,
  }) : assert(copyText != null || onTap != null);

  final Widget glyph;
  final Color iconBg;
  final String value;
  final String sub;

  /// 复制型行（WhatsApp/邮箱）：右侧灰色复制键，点击复制 [copyText]。
  final String? copyText;

  /// 导航型行（站内工单）：右侧 chevron，整行可点触发 [onTap]。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final row = Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          // 色块圆角图标（44×44）。
          Container(
            width: 44,
            height: 44,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: iconBg,
              borderRadius: BorderRadius.circular(14),
            ),
            child: glyph,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  value,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  sub,
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
                  maxLines: 2,
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          // 右侧：复制键（复制型）或 chevron（导航型）。
          if (copyText != null)
            Material(
              color: _kCsMuted,
              borderRadius: BorderRadius.circular(10),
              child: InkWell(
                key: ValueKey('csCopy_$copyText'),
                borderRadius: BorderRadius.circular(10),
                onTap: () async {
                  await Clipboard.setData(ClipboardData(text: copyText!));
                  if (!context.mounted) return;
                  showAppToast(context, l10n.csCopied);
                },
                child: const SizedBox(
                  width: 36,
                  height: 36,
                  child: Icon(Icons.content_copy_rounded, size: 16, color: AppColors.textSecondary),
                ),
              ),
            )
          else
            const Icon(Icons.chevron_right, color: AppColors.muted),
        ],
      ),
    );
    if (onTap == null) return row;
    return InkWell(
      key: ValueKey('csTicketRow_$value'),
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: row,
    );
  }
}
