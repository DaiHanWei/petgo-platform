import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/customer_service_sheet.dart';
import '../../auth/domain/auth_state.dart';
import '../data/vet_repository.dart';

/// 兽医账密登录页（Story 5.1 F1/F2/F3 · vet-login.html 1:1 还原 · 薄荷主题）。
///
/// 与用户侧 Google 流程隔离：账号 + 密码表单 → role=VET JWT → 跳兽医工作台壳。
/// **无「忘记密码」入口**（重置走运营，spec F3）——仅文案提示联系运营（原型画的
/// 「Lupa kata sandi?」链接不实现，以 spec 为准）。
class VetLoginPage extends ConsumerStatefulWidget {
  const VetLoginPage({super.key});

  @override
  ConsumerState<VetLoginPage> createState() => _VetLoginPageState();
}

class _VetLoginPageState extends ConsumerState<VetLoginPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  bool _obscure = true;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _onLogin() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(vetRepositoryProvider).login(_username.text.trim(), _password.text);
      ref.read(authControllerProvider.notifier).applyVetLogin();
      if (!mounted) return;
      context.go('/vet/workbench');
    } on DioException catch (e) {
      // 429 限流 → 专用文案；其余（含 401）统一「账号或密码错误」防枚举。
      final msg = e.response?.statusCode == 429 ? l10n.vetLoginRateLimited : l10n.vetLoginFailed;
      _showBanner(msg);
    } catch (_) {
      _showBanner(l10n.vetLoginFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showBanner(String message) {
    if (!mounted) return;
    showAppToast(context, message);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: [
            // 返回钮（薄荷浅底圆角方块）。
            Align(
              alignment: Alignment.centerLeft,
              child: Material(
                color: AppColors.vetSurface,
                borderRadius: BorderRadius.circular(12),
                child: InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: () => context.canPop() ? context.pop() : context.go('/login'),
                  child: const SizedBox(
                    width: 40,
                    height: 40,
                    child: Icon(Icons.arrow_back, size: 20, color: AppColors.vetPrimaryDeep),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
            // 品牌头：薄荷图标块 + 标题 + 副文。
            Row(
              children: [
                Container(
                  width: 52,
                  height: 52,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: AppColors.vetPrimary,
                    borderRadius: BorderRadius.circular(15),
                  ),
                  child: const Icon(Icons.medical_services_rounded, size: 26, color: AppColors.vetOnAccent),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(l10n.vetLoginPortalTitle,
                          style: const TextStyle(
                              fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.vetPrimaryDeep)),
                      const SizedBox(height: 2),
                      Text(l10n.vetLoginPortalSubtitle,
                          style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            // 薄荷浅底信息条（左薄荷竖线）。
            Container(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
              decoration: BoxDecoration(
                color: AppColors.vetSurface,
                borderRadius: BorderRadius.circular(12),
                border: const Border(left: BorderSide(color: AppColors.vetPrimary, width: 3)),
              ),
              child: RichText(
                text: TextSpan(
                  style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.vetPrimaryDeep),
                  children: [
                    TextSpan(text: l10n.vetLoginNoticePrefix),
                    TextSpan(
                        text: l10n.vetLoginNoticeBold,
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    TextSpan(text: l10n.vetLoginNoticeSuffix),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            // EMAIL TERDAFTAR
            _sectionLabel(l10n.vetLoginEmailLabel),
            const SizedBox(height: 6),
            _VetInputField(
              fieldKey: const ValueKey('vetUsernameField'),
              controller: _username,
              icon: Icons.mail_outline_rounded,
              hint: l10n.vetLoginEmailHint,
              keyboardType: TextInputType.emailAddress,
              autocorrect: false,
              enableSuggestions: false,
            ),
            const SizedBox(height: 18),
            // KATA SANDI
            _sectionLabel(l10n.vetLoginPasswordLabel),
            const SizedBox(height: 6),
            _VetInputField(
              fieldKey: const ValueKey('vetPasswordField'),
              controller: _password,
              icon: Icons.lock_outline_rounded,
              hint: '••••••••',
              obscureText: _obscure,
              suffix: IconButton(
                icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                    size: 20, color: AppColors.textTertiary),
                onPressed: () => setState(() => _obscure = !_obscure),
              ),
            ),
            const SizedBox(height: 8),
            // 无自助「忘记密码」流程——点提示弹客服抽屉联系运营（FR-29 / NFR-12，spec F3；bug 20260702-230）。
            Align(
              alignment: Alignment.centerRight,
              child: GestureDetector(
                key: const ValueKey('vetForgotHint'),
                onTap: () => showCustomerServiceSheet(context),
                behavior: HitTestBehavior.opaque,
                child: Text(
                  l10n.vetForgotHint,
                  style: const TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.vetPrimary),
                  textAlign: TextAlign.end,
                ),
              ),
            ),
            const SizedBox(height: 18),
            // 薄荷大钮「Masuk sebagai Dokter」。
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('vetLoginButton'),
                onPressed: _busy ? null : _onLogin,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.vetPrimary,
                  foregroundColor: AppColors.vetOnAccent,
                  padding: const EdgeInsets.symmetric(vertical: 15),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: _busy
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : Text(l10n.vetLoginButton,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 20),
            // 「Belum terdaftar?」分隔。
            Row(
              children: [
                const Expanded(child: Divider(color: AppColors.border)),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Text(l10n.vetLoginNotRegistered,
                      style: const TextStyle(fontSize: 12, color: AppColors.textTertiary)),
                ),
                const Expanded(child: Divider(color: AppColors.border)),
              ],
            ),
            const SizedBox(height: 14),
            // 成为合作伙伴（薄荷描边钮）。
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: () => showCustomerServiceSheet(context),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.vetPrimary,
                  side: const BorderSide(color: AppColors.vetPrimary, width: 1.5),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text('${l10n.vetLoginBecomePartner} 🤝',
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 20),
            // 底部三信任标。
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _TrustBadge(icon: Icons.shield_outlined, label: l10n.vetLoginTrustEncrypted),
                const SizedBox(width: 18),
                _TrustBadge(icon: Icons.verified_outlined, label: l10n.vetLoginTrustVerified),
                const SizedBox(width: 18),
                _TrustBadge(icon: Icons.info_outline, label: l10n.vetLoginTrustSecure),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) => Text(text,
      style: const TextStyle(
          fontSize: 11.5,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.5,
          color: AppColors.textSecondary));

}

/// 兽医登录输入框（原型 vet-login）：聚焦时**图标转薄荷 + 1.5px 薄荷边 + 3px 薄荷光晕圈**；
/// 未聚焦灰边、灰图标。自管 [FocusNode] 以驱动聚焦态重建。
class _VetInputField extends StatefulWidget {
  const _VetInputField({
    required this.fieldKey,
    required this.controller,
    required this.icon,
    this.hint,
    this.obscureText = false,
    this.keyboardType,
    this.autocorrect = true,
    this.enableSuggestions = true,
    this.suffix,
  });

  final Key fieldKey;
  final TextEditingController controller;
  final IconData icon;
  final String? hint;
  final bool obscureText;
  final TextInputType? keyboardType;
  final bool autocorrect;
  final bool enableSuggestions;
  final Widget? suffix;

  @override
  State<_VetInputField> createState() => _VetInputFieldState();
}

class _VetInputFieldState extends State<_VetInputField> {
  final FocusNode _node = FocusNode();

  @override
  void initState() {
    super.initState();
    _node.addListener(_onFocusChange);
  }

  void _onFocusChange() => setState(() {});

  @override
  void dispose() {
    _node.removeListener(_onFocusChange);
    _node.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final focused = _node.hasFocus;
    final accent = focused ? AppColors.vetPrimary : AppColors.textTertiary;
    return DecoratedBox(
      // 原型 box-shadow:0 0 0 3px rgba(91,203,187,.08)：聚焦时边框外一圈薄荷光晕（spread 3 / blur 0）。
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        boxShadow: focused
            ? [
                BoxShadow(
                    color: AppColors.vetPrimary.withValues(alpha: 0.08),
                    blurRadius: 0,
                    spreadRadius: 3),
              ]
            : null,
      ),
      child: TextField(
        key: widget.fieldKey,
        focusNode: _node,
        controller: widget.controller,
        obscureText: widget.obscureText,
        keyboardType: widget.keyboardType,
        autocorrect: widget.autocorrect,
        enableSuggestions: widget.enableSuggestions,
        decoration: InputDecoration(
          prefixIcon: Icon(widget.icon, size: 20, color: accent),
          suffixIcon: widget.suffix,
          hintText: widget.hint,
          hintStyle: const TextStyle(color: AppColors.muted, fontSize: 14),
          isDense: true,
          filled: true,
          fillColor: AppColors.surface,
          contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.line, width: 1.5),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: AppColors.vetPrimary, width: 1.5),
          ),
        ),
      ),
    );
  }
}

/// 底部信任标（图标 + 标签，薄荷灰）。
class _TrustBadge extends StatelessWidget {
  const _TrustBadge({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: AppColors.vetPrimary),
        const SizedBox(width: 4),
        Text(label, style: const TextStyle(fontSize: 10.5, color: AppColors.textSecondary)),
      ],
    );
  }
}
