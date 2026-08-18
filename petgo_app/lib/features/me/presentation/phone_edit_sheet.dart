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
  static Future<bool> open(BuildContext context, {required String entry}) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => PhoneEditSheet(entry: entry),
    );
    return saved ?? false;
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
    _controller = TextEditingController(text: phone ?? '');
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
          Text(l10n.phoneEditTitle,
              style: const TextStyle(
                  fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const ValueKey('phoneInput'),
            controller: _controller,
            keyboardType: TextInputType.phone,
            autofocus: true,
            decoration: InputDecoration(
              hintText: l10n.phoneEditHint,
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          Row(
            children: [
              Expanded(
                child: TextButton(
                  key: const ValueKey('phoneCancel'),
                  onPressed: () => Navigator.of(context).pop(false),
                  child: Text(l10n.commonCancel),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: FilledButton(
                  key: const ValueKey('phoneSave'),
                  // ⚠️ 输入框为空时**照样可点** —— 那是清空保存（撤回），不是无效操作。
                  onPressed: _saving ? null : _save,
                  style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
                  child: Text(l10n.commonSave),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
