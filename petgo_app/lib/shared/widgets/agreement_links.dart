import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/config/legal_urls.dart';
import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';

/// 协议链接（Text Link 模式，FR-0D：条款+隐私两份可点链接，**无勾选框**）。
///
/// 审核合规硬要求（EULA/条款必须在注册或登录**之前**展示）：任何带登录 CTA 的面
/// （完整登录页、强弹窗、软浮层、兽医登录页）都必须挂载本组件，新增登录入口时勿漏。
class AgreementLinks extends StatelessWidget {
  const AgreementLinks({super.key});

  Future<void> _open(String url) async {
    final uri = Uri.tryParse(url);
    if (uri != null) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    const baseStyle = TextStyle(fontSize: 10.5, color: AppColors.textDisclaimer, height: 1.7);
    final linkStyle = baseStyle.copyWith(
      color: AppColors.muted,
      decoration: TextDecoration.underline,
    );
    return Wrap(
      alignment: WrapAlignment.center,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(l10n.loginAgreementPrefix, style: baseStyle),
        GestureDetector(
          key: const ValueKey('termsLink'),
          onTap: () => _open(kTermsUrl),
          child: Text(l10n.termsOfService, style: linkStyle),
        ),
        Text(l10n.loginAgreementAnd, style: baseStyle),
        GestureDetector(
          key: const ValueKey('privacyLink'),
          onTap: () => _open(kPrivacyUrl),
          child: Text(l10n.privacyPolicy, style: linkStyle),
        ),
      ],
    );
  }
}
