import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../l10n/app_localizations.dart';
import '../data/me_repository.dart';
import '../domain/auth_state.dart';

/// 昵称确认页（Story 1.6 F1，FR-0E）。
///
/// 默认填充 Google displayName；实时计数 ≤20（客户端预校验体验，服务端权威）；
/// 超出禁用「继续」。确认 → PATCH 昵称 → 进宠物状态选择页。
const int kNicknameMaxLength = 20;

class NicknamePage extends ConsumerStatefulWidget {
  const NicknamePage({super.key});

  @override
  ConsumerState<NicknamePage> createState() => _NicknamePageState();
}

class _NicknamePageState extends ConsumerState<NicknamePage> {
  late final TextEditingController _controller;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    // 优先回显已填昵称（从状态选择页返回时保留，AC4）；否则默认 Google displayName。
    final profile = ref.read(authControllerProvider).profile;
    final initial = (profile?.nickname?.trim().isNotEmpty ?? false)
        ? profile!.nickname!
        : (profile?.displayName ?? '');
    _controller = TextEditingController(text: initial);
  }

  /// FR-0E 返回键语义（AC4）：在昵称确认页按返回 → 退出登录流程、回未登录首页，
  /// **账号不创建**（不调任何写账号端点，清本地登录态回游客）。下次重新从 Google 授权开始。
  void _onBackExitFlow() {
    ref.read(authControllerProvider.notifier).toGuest();
    context.go('/home');
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _valid {
    final t = _controller.text.trim();
    return t.isNotEmpty && t.length <= kNicknameMaxLength;
  }

  Future<void> _onContinue() async {
    if (!_valid || _busy) return;
    Analytics.capture('onboarding_nickname_submitted');
    setState(() => _busy = true);
    try {
      final profile = await ref.read(meRepositoryProvider).updateNickname(_controller.text.trim());
      ref.read(authControllerProvider.notifier).applyProfile(profile);
      if (!mounted) return;
      context.go('/onboarding/pet-status');
    } catch (_) {
      if (mounted) {
        showAppToast(context, AppLocalizations.of(context).loginFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final count = _controller.text.characters.length;
    final profile = ref.read(authControllerProvider).profile;
    final email = profile?.email ?? '';
    final avatarLetter = (_controller.text.trim().isNotEmpty ? _controller.text.trim() : 'A')
        .characters.first.toUpperCase();
    final over = count > kNicknameMaxLength;
    return PopScope(
      canPop: false, // 返回键语义自定义（AC4）：昵称页返回=退出登录流程、不建账号
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onBackExitFlow();
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ── 顶部：返回 + 进度条(1/2) ──
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                child: Row(
                  children: [
                    GestureDetector(
                      key: const ValueKey('nicknameBack'),
                      onTap: _onBackExitFlow,
                      child: Container(
                        width: 36, height: 36,
                        decoration: BoxDecoration(color: const Color(0xFFEFEDF3), borderRadius: BorderRadius.circular(11)),
                        child: const Icon(Icons.arrow_back, size: 18, color: Color(0xFF544864)),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Row(children: [
                        Expanded(child: Container(height: 3, decoration: BoxDecoration(color: const Color(0xFF845EC9), borderRadius: BorderRadius.circular(999)))),
                        const SizedBox(width: 5),
                        Expanded(child: Container(height: 3, decoration: BoxDecoration(color: const Color(0xFFE6E6E6), borderRadius: BorderRadius.circular(999)))),
                      ]),
                    ),
                    const SizedBox(width: 12),
                    const Text('1 / 2', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: Color(0xFF9690A6))),
                  ],
                ),
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(24, 34, 24, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(l10n.onboardingNicknameTitle,
                          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, height: 1.3, color: Color(0xFF2E2A45))),
                      const SizedBox(height: 6),
                      Text(l10n.onboardingNicknameSubtitle,
                          style: const TextStyle(fontSize: 13, color: Color(0xFF9690A6))),
                      const SizedBox(height: 28),
                      // Google avatar + email 卡
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
                        decoration: BoxDecoration(color: const Color(0xFFF8F2FF), borderRadius: BorderRadius.circular(15)),
                        child: Row(children: [
                          Container(
                            width: 46, height: 46,
                            decoration: const BoxDecoration(
                              shape: BoxShape.circle,
                              gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF9E83DA), Color(0xFF845EC9)]),
                            ),
                            child: Center(child: Text(avatarLetter, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.white))),
                          ),
                          const SizedBox(width: 13),
                          Expanded(
                            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                              Text(_controller.text.trim().isEmpty ? (profile?.displayName ?? '') : _controller.text.trim(),
                                  maxLines: 1, overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: Color(0xFF2E2A45))),
                              if (email.isNotEmpty) ...[
                                const SizedBox(height: 2),
                                Text(email, maxLines: 1, overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(fontSize: 11, color: Color(0xFF9690A6))),
                              ],
                            ]),
                          ),
                        ]),
                      ),
                      const SizedBox(height: 24),
                      // NAMA TAMPILAN 标签
                      Text(l10n.onboardingNicknameLabel.toUpperCase(),
                          style: const TextStyle(fontSize: 10.5, fontWeight: FontWeight.w700, letterSpacing: 0.6, color: Color(0xFF544864))),
                      const SizedBox(height: 7),
                      // 紫 focus 输入框
                      Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(13),
                          border: Border.all(color: over ? const Color(0xFFF0425A) : const Color(0xFF845EC9), width: 1.5),
                          boxShadow: [BoxShadow(color: const Color(0xFF845EC9).withValues(alpha: 0.08), blurRadius: 0, spreadRadius: 3)],
                        ),
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
                        child: Row(children: [
                          Expanded(
                            child: TextField(
                              key: const ValueKey('nicknameField'),
                              controller: _controller,
                              maxLength: kNicknameMaxLength + 5,
                              onChanged: (_) => setState(() {}),
                              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w500, color: Color(0xFF2E2A45)),
                              decoration: const InputDecoration(
                                counterText: '',
                                border: InputBorder.none,
                                isDense: true,
                                contentPadding: EdgeInsets.symmetric(vertical: 14),
                              ),
                            ),
                          ),
                          const Icon(Icons.edit_outlined, size: 15, color: Color(0xFFC2B0EC)),
                        ]),
                      ),
                      const SizedBox(height: 6),
                      Text('$count / $kNicknameMaxLength',
                          textAlign: TextAlign.right,
                          style: TextStyle(fontSize: 11, color: over ? const Color(0xFFF0425A) : const Color(0xFF9690A6))),
                      const SizedBox(height: 10),
                      Text(l10n.onboardingNicknameHelper,
                          style: const TextStyle(fontSize: 12, height: 1.5, color: Color(0xFF9690A6))),
                      const SizedBox(height: 44),
                      // Lanjut 按钮
                      _ContinueButton(enabled: _valid && !_busy, busy: _busy, label: l10n.onboardingContinue, onTap: _onContinue),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 主 CTA 按钮（紫 + 阴影，禁用变灰）。
class _ContinueButton extends StatelessWidget {
  const _ContinueButton({required this.enabled, required this.busy, required this.label, required this.onTap});
  final bool enabled, busy;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        boxShadow: enabled ? [BoxShadow(color: const Color(0xFF845EC9).withValues(alpha: 0.28), blurRadius: 20, offset: const Offset(0, 8))] : null,
      ),
      child: FilledButton(
        key: const ValueKey('nicknameContinue'),
        onPressed: enabled ? onTap : null,
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xFF845EC9),
          disabledBackgroundColor: const Color(0xFFC2B0EC),
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 15),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
        child: busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
      ),
    );
  }
}
