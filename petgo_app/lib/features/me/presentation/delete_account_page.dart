import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';

/// 账号注销整页（P-43 · Story 7.3 AC2）。原型 delete-account 1:1：
/// 红圈⚠️ 警示 → 「将被永久删除」清单 → 输确认短语激活红钮 → DELETE /me（级联删除/匿名化，D1/D2）→ 回游客。
///
/// 注：app 为**立即级联删除**（非 30 天宽限期），故不展示原型那条「30天可撤销」冷静期提示（避免误导，守 7.3 合规）。
class DeleteAccountPage extends ConsumerStatefulWidget {
  const DeleteAccountPage({super.key});

  @override
  ConsumerState<DeleteAccountPage> createState() => _DeleteAccountPageState();
}

class _DeleteAccountPageState extends ConsumerState<DeleteAccountPage> {
  final _controller = TextEditingController();
  bool _deleting = false;

  static const Color _danger = AppColors.popRed; // #F0425A
  static const Color _dangerTint = Color(0xFFFDE7EB);
  static const Color _dangerText = Color(0xFFC4263C);

  /// 注销确认短语——须与后端 DeleteAccountRequest.CONFIRM_PHRASE 完全一致（固定英文串，locale 无关）。
  static const String _confirmPhrase = 'DELETE';

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _delete(String phrase) async {
    setState(() => _deleting = true);
    try {
      await ref.read(authRepositoryProvider).deleteAccount(phrase);
      ref.read(authControllerProvider.notifier).toGuest();
      if (mounted) context.go('/home');
    } catch (_) {
      if (!mounted) return;
      setState(() => _deleting = false);
      final l10n = AppLocalizations.of(context);
      showAppToast(context, l10n.deleteAccountFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // P-43：输入注销确认短语「DELETE」（须与后端 CONFIRM_PHRASE 完全一致才激活删除）。
    final matched = _controller.text.trim() == _confirmPhrase;

    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(22, 4, 22, 28),
          children: [
            // 顶栏：返回 + 标题。
            Row(
              children: [
                _backBtn(),
                const SizedBox(width: 12),
                Text(l10n.meDeleteAccount,
                    style: const TextStyle(
                        fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ],
            ),
            const SizedBox(height: 20),
            // 警示图标 + 标题 + 永久不可撤销。
            Column(
              children: [
                Container(
                  width: 72,
                  height: 72,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(color: _dangerTint, shape: BoxShape.circle),
                  child: const Text('⚠️', style: TextStyle(fontSize: 32)),
                ),
                const SizedBox(height: 12),
                Text(l10n.deleteAccountWarnTitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 6),
                // 「permanen」红字（原型 <strong color:#F0425A>）。
                _permanentWarning(l10n),
              ],
            ),
            const SizedBox(height: 22),
            // 「将被永久删除」清单（红浅底盒）。
            Container(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
              decoration:
                  BoxDecoration(color: _dangerTint, borderRadius: BorderRadius.circular(14)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.deletionListHeading,
                      style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.4,
                          color: _dangerText)),
                  const SizedBox(height: 10),
                  _item(l10n.deletionItemPets),
                  _item(l10n.deletionItemGrowth),
                  _item(l10n.deletionItemPosts),
                  _item(l10n.deletionItemConsults),
                  _item(l10n.deletionItemGoogle),
                ],
              ),
            ),
            // 注销为「立即级联删除/匿名化」（D1/D2），不展示 30 天冷静期，避免与不可撤销实现矛盾。
            const SizedBox(height: 22),
            // 输入确认短语「DELETE」（红边框 + 红光晕）。
            Text(l10n.deleteAccountPhraseLabel,
                style: const TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.5,
                    color: AppColors.textSecondary)),
            const SizedBox(height: 7),
            Container(
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: _danger, width: 1.5),
                boxShadow: [
                  BoxShadow(
                      color: _danger.withValues(alpha: 0.08), blurRadius: 0, spreadRadius: 3),
                ],
              ),
              padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 4),
              child: TextField(
                key: const ValueKey('deleteConfirmField'),
                controller: _controller,
                autocorrect: false,
                onChanged: (_) => setState(() {}),
                style: const TextStyle(fontSize: 14, color: AppColors.ink),
                decoration: const InputDecoration(
                  isCollapsed: true,
                  contentPadding: EdgeInsets.symmetric(vertical: 11),
                  border: InputBorder.none,
                  hintText: _confirmPhrase,
                  hintStyle: TextStyle(color: AppColors.muted, fontSize: 14),
                ),
              ),
            ),
            const SizedBox(height: 5),
            Text(l10n.deleteAccountConfirmHint,
                style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
            const SizedBox(height: 22),
            // 红色删除按钮（邮箱匹配才激活）。
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('deleteAccountConfirmYes'),
                onPressed: (matched && !_deleting) ? () => _delete(_confirmPhrase) : null,
                style: FilledButton.styleFrom(
                  backgroundColor: _danger,
                  disabledBackgroundColor: const Color(0xFFB6B6B6),
                  foregroundColor: Colors.white,
                  disabledForegroundColor: Colors.white,
                  elevation: matched ? 4 : 0,
                  shadowColor: _danger.withValues(alpha: 0.30),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: _deleting
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : Text(l10n.deleteAccountConfirmYes,
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 11),
            // 取消（描边）。
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: _deleting ? null : () => context.pop(),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.textSecondary,
                  side: const BorderSide(color: AppColors.line, width: 1.5),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.deleteAccountCancel, style: const TextStyle(fontSize: 14)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 永久警示：把句中「permanen/permanent」标红（原型 <strong color:#F0425A>）。
  Widget _permanentWarning(AppLocalizations l10n) {
    final word = l10n.deleteAccountPermanentWord;
    final full = l10n.deleteAccountWarnBody(word);
    final i = full.indexOf(word);
    const base = TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2);
    if (i < 0) {
      return Text(full, textAlign: TextAlign.center, style: base);
    }
    return Text.rich(
      TextSpan(style: base, children: [
        TextSpan(text: full.substring(0, i)),
        TextSpan(
            text: word,
            style: const TextStyle(color: _danger, fontWeight: FontWeight.w700)),
        TextSpan(text: full.substring(i + word.length)),
      ]),
      textAlign: TextAlign.center,
    );
  }

  Widget _backBtn() => Material(
        color: const Color(0xFFEFEDF3), // bg-muted
        borderRadius: BorderRadius.circular(11),
        child: InkWell(
          key: const ValueKey('deleteAccountBack'),
          borderRadius: BorderRadius.circular(11),
          onTap: () => context.canPop() ? context.pop() : context.go('/me/settings'),
          child: const SizedBox(
            width: 36,
            height: 36,
            child: Icon(Icons.arrow_back, size: 18, color: AppColors.ink2),
          ),
        ),
      );

  Widget _item(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 7),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              margin: const EdgeInsets.only(top: 6),
              width: 6,
              height: 6,
              decoration: const BoxDecoration(color: _danger, shape: BoxShape.circle),
            ),
            const SizedBox(width: 9),
            Expanded(
              child: Text(text,
                  style: const TextStyle(fontSize: 13, height: 1.4, color: AppColors.ink)),
            ),
          ],
        ),
      );
}
