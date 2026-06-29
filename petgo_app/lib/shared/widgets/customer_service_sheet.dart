import 'package:flutter/material.dart';
import 'app_toast.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 客服联系方式（固定联系数据，非可翻译文案）。
const String _kCsWhatsappNumber = '081290906953';
const String _kCsEmail = 'cs@tailtopia.id';

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
    builder: (ctx) => SafeArea(
      child: Padding(
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
            const SizedBox(height: 18),
            // WhatsApp：绿底 + 真 WhatsApp logo，号码可复制。
            _CsContactRow(
              glyph: SvgPicture.asset(
                'assets/brand/whatsapp.svg',
                width: 22,
                height: 22,
                colorFilter: const ColorFilter.mode(_kCsWaGreen, BlendMode.srcIn),
              ),
              iconBg: _kCsWaTint,
              value: _kCsWhatsappNumber,
              sub: '${l10n.csWhatsappLabel} · ${l10n.csWhatsappNote}',
              copyText: _kCsWhatsappNumber,
            ),
            const Divider(height: 1, thickness: 1, color: AppColors.line2),
            // 邮箱：紫底紫标，地址可复制。
            _CsContactRow(
              glyph: const Icon(Icons.mail_outline, size: 22, color: AppColors.mint),
              iconBg: AppColors.mintTint2,
              value: _kCsEmail,
              sub: '${l10n.csEmailLabel} · ${l10n.csEmailNote}',
              copyText: _kCsEmail,
            ),
            const SizedBox(height: 16),
            // 取消：灰色填充（非描边），对齐原型 .cancel-btn。
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
                l10n.commonCancel,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

/// 客服联系条（原型 .contact-row）：色块圆角图标 + 值 + 「标签 · 时效」副行 + 右侧独立灰色复制键。
class _CsContactRow extends StatelessWidget {
  const _CsContactRow({
    required this.glyph,
    required this.iconBg,
    required this.value,
    required this.sub,
    required this.copyText,
  });

  final Widget glyph;
  final Color iconBg;
  final String value;
  final String sub;
  final String copyText;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
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
          // 独立灰色复制键（36×36）。
          Material(
            color: _kCsMuted,
            borderRadius: BorderRadius.circular(10),
            child: InkWell(
              key: ValueKey('csCopy_$copyText'),
              borderRadius: BorderRadius.circular(10),
              onTap: () async {
                await Clipboard.setData(ClipboardData(text: copyText));
                if (!context.mounted) return;
                showAppToast(context, l10n.csCopied);
              },
              child: const SizedBox(
                width: 36,
                height: 36,
                child: Icon(Icons.content_copy_rounded, size: 16, color: AppColors.textSecondary),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
