import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';

/// 手机号编辑抽屉（V1.1.6 Story 7.1 · FR-70）。
///
/// ## 用户可以随时清掉
/// 🛡 清空输入框保存 = **撤回**（个人数据保护法的删除权）。
/// **不做任何二次确认** —— 撤回是用户的权利，不该被"你确定吗"劝阻一次。
///
/// ## 不验证、不用于登录
/// 只做印尼手机号的**基础格式校验**（服务端权威），不发验证码、不当登录凭据。
///
/// ## ⚠️ 本 story 还没有入口
/// 软引导与设置页入口都属 Story 7-2（其中软引导的时机口径至今未定义，是 OA-5 唯一还挡着的东西）。
/// 所以这个抽屉写完之后**暂时没有可达路径** —— 与 Story 4.1 的情形相同。
/// [entry] 做成必填就是为了逼调用方在接入口时说清"从哪来"：
/// 埋点的判读（软引导填写率）完全依赖它。
class PhoneEditSheet extends ConsumerStatefulWidget {
  const PhoneEditSheet({super.key, required this.entry});

  /// 入口来源（埋点用）：`soft_prompt` / `me_page`。
  final String entry;

  /// 打开抽屉。返回是否保存成功（调用方可据此刷新）。
  static Future<bool> open(BuildContext context, {required String entry}) async =>
      await openDetailed(context, entry: entry) ?? false;

  /// 打开抽屉，**保留三态**：`true` 保存成功 · `false` 点了「取消」· `null` 划走/点遮罩关掉。
  ///
  /// 🔴 三态不是多余的精细度 —— 埋点清单 §3 对 E-5 的 `action` 明写
  /// 「`skipped` 与 `dismissed` 要分开：**前者是拒绝，后者可能只是没看懂**」。
  /// 合并成一个"没保存"，这条判读就永久做不出来（而事后补埋点补不回已流失的数据）。
  ///
  /// ⚠️ [open] 仍折叠成 bool，是因为设置页那个入口只关心"要不要刷新"。
  static Future<bool?> openDetailed(BuildContext context,
      {required String entry}) async {
    return showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => PhoneEditSheet(entry: entry),
    );
  }

  @override
  ConsumerState<PhoneEditSheet> createState() => _PhoneEditSheetState();
}

class _PhoneEditSheetState extends ConsumerState<PhoneEditSheet> {
  late final TextEditingController _controller;

  /// 打开时是否已有号码 —— 埋点要区分「首次填写」与「修改」。
  late final bool _hadPhoneBefore;

  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final phone = ref.read(authControllerProvider).profile?.phone;
    _hadPhoneBefore = phone != null && phone.isNotEmpty;
    // 编辑时展示**完整号码**（设置页列表那处才脱敏）。
    // ⚠️ 剥掉 `+62`（bug 20260826 对设计稿）：国家码已经由左侧前缀芯片常驻展示，
    //    再留在输入框里就成了「+62 +62」。后端归一后存的是 `+62…` 形态，故必剥。
    //    只剥这一种前缀 —— 后端 IndonesianPhone 归一后不会有 `62…` / `0…` 落库。
    final national = (phone ?? '').startsWith('+62') ? phone!.substring(3) : (phone ?? '');
    _controller = TextEditingController(text: national);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    final l10n = AppLocalizations.of(context);
    final input = _controller.text.trim();
    try {
      // 🔴 空串照样发出去 —— 那是**清空**语义，服务端据此写回空值。
      // 若在这里"空就不发"，撤回权会静默落空（用户以为删了，其实没删）。
      final updated = await ref.read(meRepositoryProvider).updatePhone(input);
      ref.read(authControllerProvider.notifier).applyProfile(updated);
      Analytics.capture('me_phone_save_succeeded', {
        'entry': widget.entry,
        // 清空也走成功路径，但它不是"填写"——用 is_first_time=false 表达，
        // 判读软引导填写率时按 entry 过滤即可。
        'is_first_time': !_hadPhoneBefore && input.isNotEmpty,
      });
      if (!mounted) return;
      showAppToast(context, l10n.phoneSaveSuccess);
      Navigator.of(context).pop(true);
    } on DioException catch (e) {
      final formatError = e.response?.statusCode == 422;
      if (formatError) {
        // 🔴 这条事件是判断「校验规则是不是太严在挡人」的唯一依据 ——
        // 失败率偏高说明是我们卡太严，而不是用户填错。
        Analytics.capture('me_phone_save_error_shown', {'entry': widget.entry});
      }
      if (!mounted) return;
      showAppToast(context,
          formatError ? l10n.phoneSaveFormatError : l10n.phoneSaveNetworkError);
    } catch (_) {
      if (!mounted) return;
      showAppToast(context, l10n.phoneSaveNetworkError);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SingleChildScrollView(
      padding: EdgeInsets.only(
        left: 22,
        right: 22,
        top: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 32,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.border,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          Center(
            child: Text(l10n.phoneEditTitle,
                style: const TextStyle(
                    fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.ink)),
          ),
          const SizedBox(height: AppSpacing.lg),
          // 字段标签（设计稿：全大写、字距略开、弱化色）。
          Text(l10n.phoneEditFieldLabel,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.8,
                color: AppColors.muted,
              )),
          const SizedBox(height: AppSpacing.sm),
          // 输入行：🇮🇩 +62 常驻前缀 | 分隔线 | 只填国内部分。
          //
          // 🔴 前缀不是装饰：后端 `IndonesianPhone` 接受 `812…` / `0812…` / `+62812…` 三种写法
          //    并统一归一成 `+62…`，所以「前缀常驻 + 只填 812…」这条路是通的，
          //    用户少打三个字符，也不会再有人纠结要不要带 0。
          //    ⚠️ 改前缀文案前先看那个类 —— 它才是真正决定什么能存进去的地方。
          DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppColors.mint, width: 1.6),
            ),
            child: Row(
              children: [
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 14),
                  child: Text('🇮🇩 +62',
                      style: TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.ink)),
                ),
                Container(width: 1, height: 26, color: AppColors.line2),
                Expanded(
                  child: TextField(
                    key: const ValueKey('phoneInput'),
                    controller: _controller,
                    keyboardType: TextInputType.phone,
                    autofocus: true,
                    style: const TextStyle(fontSize: 16, color: AppColors.ink),
                    decoration: InputDecoration(
                      hintText: l10n.phoneEditHint,
                      hintStyle: const TextStyle(color: AppColors.muted),
                      border: InputBorder.none,
                      contentPadding:
                          const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          // 设计稿是**上下两个通栏按钮**，不是左右并排：主操作占满一行更好点，
          // 「取消」降级为描边按钮放在下面（并排时两个等宽按钮谁是主操作看不出来）。
          SizedBox(
            height: 54,
            child: FilledButton(
              key: const ValueKey('phoneSave'),
              // ⚠️ 输入框为空时**照样可点** —— 那是清空保存（撤回），不是无效操作。
              onPressed: _saving ? null : _save,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.commonSave,
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            height: 54,
            child: OutlinedButton(
              key: const ValueKey('phoneCancel'),
              onPressed: () => Navigator.of(context).pop(false),
              style: OutlinedButton.styleFrom(
                side: const BorderSide(color: AppColors.line2),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.commonCancel,
                  style: const TextStyle(
                      fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ),
          ),
        ],
      ),
    );
  }
}
