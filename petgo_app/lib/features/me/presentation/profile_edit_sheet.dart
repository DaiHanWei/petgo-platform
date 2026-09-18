import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../media/domain/media_upload_use_case.dart';

/// 资料编辑底抽屉（原型 `profil-edit-sheet`）。
///
/// ## 🔴 V1.3.0 batch-b1 Story 2.4：从 `me_page.dart` **原样抽出**，行为与字段一字未改
/// 「我的」Tab 与**公开主页的自己视角**要弹同一个抽屉（AC3 原文：跳既有抽屉、**不重画**）。
/// 抽出来是为了让两处调**同一份实现** —— 复制一份的话，昵称长度、签名上限、保存失败提示
/// 这些细节迟早各改各的。
///
/// ⚠️ **不要在这里加"从哪儿打开的"分支**：两处的抽屉本来就该长得一模一样。
/// 真出现差异需求，先回 AC3 改口径。

/// 编辑资料底抽屉（原型 profil-edit-sheet）：头像区（展示 + Ganti Foto 占位）+ 昵称可编辑 + 邮箱只读 + 保存/取消。
///
/// 决策 #6（2026-06-18）：头像上传触媒体流较复杂，本期降级——保留头像展示 + 「Ganti Foto」入口提示「待接入」，
/// 不阻塞 sheet 视觉还原；仅昵称走 updateNickname 落库。
Future<void> openProfileEditSheet(BuildContext context, WidgetRef ref) async {
  final l10n = AppLocalizations.of(context);
  final profile = ref.read(authControllerProvider).profile;
  final controller = TextEditingController(
    text: profile?.nickname ?? profile?.displayName ?? '',
  );
  // bug 20260721-327：一句话个性签名（用户级）。
  final sigController = TextEditingController(text: profile?.signature ?? '');
  final result = await showModalBottomSheet<({String nickname, String signature})>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (ctx) => SingleChildScrollView(
      // padding（含键盘 viewInsets）直接加在滚动视图上：sheet 按内容自适应高度、
      // 内容可滚动且 Save/Cancel 始终可见；viewInsets 区落在键盘之后，不显示为白区。
      // （用户反馈：之前结构把 sheet 撑满高 → 底部大片白遮住按钮。）
      padding: EdgeInsets.only(
        left: 22,
        right: 22,
        top: 20,
        bottom: MediaQuery.of(ctx).viewInsets.bottom + 32,
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
                color: AppColors.line,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            l10n.meEditProfileTitle,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 20),
          // 头像区（展示 + Ganti Foto 换头像）。bug 20260721-287：接真实换头像流程
          // （原为「待接入」假 toast，但 _changeAvatar 上传流程本已实现，点头像本体即用）。
          Center(
            child: Consumer(
              builder: (ctx, ref, _) {
                // 实时头像：换成功后 applyProfile 更新 authController，预览随之刷新。
                final p = ref.watch(authControllerProvider).profile;
                return Column(
                  children: [
                    InitialAvatar(
                      avatarUrl: p?.avatarUrl,
                      nickname: p?.nickname ?? '',
                      radius: 38,
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      key: const ValueKey('meEditPhoto'),
                      onPressed: () => changeAvatar(ctx, ref),
                      child: Text(
                        l10n.meEditPhotoChange,
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.mint,
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
          const SizedBox(height: 14),
          // 昵称（可编辑）。
          Text(
            l10n.meEditNicknameLabel.toUpperCase(),
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('nicknameField'),
            controller: controller,
            maxLength: 20, // 客户端预校验（体验层），服务端权威 ≤20
            autofocus: true,
            decoration: InputDecoration(
              counterText: '',
              filled: true,
              fillColor: AppColors.surface,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 15,
                vertical: 13,
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(
                  color: AppColors.mint,
                  width: 1.5,
                ),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(
                  color: AppColors.mint,
                  width: 1.5,
                ),
              ),
            ),
          ),
          const SizedBox(height: 14),
          // 一句话个性签名（bug 20260721-327）。
          Text(
            l10n.meEditSignatureLabel.toUpperCase(),
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('signatureField'),
            controller: sigController,
            maxLength: 60,
            maxLines: 2,
            minLines: 1,
            decoration: InputDecoration(
              counterText: '',
              hintText: l10n.meEditSignatureHint,
              filled: true,
              fillColor: AppColors.surface,
              contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
              ),
            ),
          ),
          const SizedBox(height: 14),
          // 邮箱（只读）。
          Text(
            l10n.meEditEmailLabel.toUpperCase(),
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
            decoration: BoxDecoration(
              color: AppColors.cream2,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.line, width: 1.5),
            ),
            child: Text(
              profile?.email ?? '',
              style: const TextStyle(
                fontSize: 14,
                color: AppColors.textTertiary,
              ),
            ),
          ),
          const SizedBox(height: 22),
          FilledButton(
            key: const ValueKey('meEditSaveButton'),
            onPressed: () => Navigator.of(ctx).pop(
                (nickname: controller.text.trim(), signature: sigController.text.trim())),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
            child: Text(
              l10n.meEditSave,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: () => Navigator.of(ctx).pop(),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.textSecondary,
              side: const BorderSide(color: AppColors.line, width: 1.5),
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
            child: Text(
              l10n.meEditCancel,
              style: const TextStyle(fontSize: 14),
            ),
          ),
        ],
      ),
    ),
  );
  // ⚠️ **既有行为，本 story 刻意不改**：昵称为空时整表当 no-op 返回 ——
  // 用户同时改的签名会一并丢弃，而且没有任何校验提示（保存按钮也不置灰）。
  // 这是从「我的」Tab 原样搬过来的，而 AC5 要求本次抽取「行为与字段一字不改」。
  // 已在 story 的 Completion Notes 里标出，留给产品决定怎么改。
  if (result == null || result.nickname.isEmpty || !context.mounted) return;
  // 🔴 **依赖在 await 之前取到手**（V1.3.0 batch-b1 Story 2.4 抽出时补）。
  //
  // 原写法是 await 之后才 `ref.read(...)`。在「我的」Tab 上看不出问题（那个 Tab 常驻、
  // 不会被卸载），但本抽屉现在也从**公开主页**弹出 —— 那是一条可 pop 的路由。
  // 保存请求在途时用户返回退页，`ref` 已随页面销毁，riverpod 3 的 `_assertNotDisposed()`
  // 会**真的抛 StateError**（不是 assert，release 也抛），而它正好落进下面那个
  // `catch (_)`：服务端已经落库、本地 profile 不更新、用户还看不到任何提示，
  // 「我的」Tab 会一直显示旧昵称（code-review 2026-09-15）。
  final meRepository = ref.read(meRepositoryProvider);
  final authNotifier = ref.read(authControllerProvider.notifier);
  try {
    final updated = await meRepository.updateProfile(
        nickname: result.nickname, signature: result.signature);
    // authController 是全局常驻 provider，跨 await 持有它的 notifier 是安全的。
    authNotifier.applyProfile(updated);
  } catch (_) {
    if (context.mounted) {
      showAppToast(context, l10n.meNicknameSaveFailed);
    }
  }
}

/// Story B：换头像 —— 选图 → 公开桶直传(CDN) → PATCH /me avatarUrl → 刷新资料。
///
/// 失败不再静默吞：按「上传 / 保存」两段分别捕获，提示**具体失败原因**(含状态码)，
/// 便于真机现场定位「没报错却不生效」类问题。成功给一次确认提示。
///
/// 内容审核 cm-5（D-CM2 / 方案 §3.4，有意权衡）：头像更换后**立即对所有人（含本人）可见**，
/// **不加任何「审核中」标签/遮挡**——审核是后端「先放行、后异步图像审核」，可见窗口期为本版本有意接受的取舍
/// （靠异步 + 举报兜底，不做「先审后显」）。若判违规，后端把 avatarUrl 重置为平台默认头像常量并推 AVATAR_RESET
/// 通知，前端照常渲染该 URL 即可（无需分支判断是否被重置）。切勿在此新增审核态 UI。
Future<void> changeAvatar(BuildContext context, WidgetRef ref) async {
  final l10n = AppLocalizations.of(context);
  // 🔴 三个依赖**全部在任何 await 之前取到手**（Story 2.4 抽出时补）。
  //
  // 这个函数是 fire-and-forget 地挂在抽屉里那个「Ganti Foto」上的（调用方不 await 它），
  // 而它自己要走「选图 → 上传 → PATCH」三段异步。上传期间用户收起抽屉 / 退出页面，
  // 原写法在最后一段才 `ref.read(meRepositoryProvider)` —— 此时 ref 已销毁，
  // riverpod 3 抛 StateError，**而那一抛正好被下面的 catch 吞掉**：
  // 图已经传上去了，`PATCH /me avatarUrl` 却根本没发，用户什么提示也没有
  // —— 与本函数「失败不再静默吞」的初衷正相反（code-review 2026-09-15）。
  final useCase = ref.read(mediaUploadUseCaseProvider);
  final meRepository = ref.read(meRepositoryProvider);
  final authNotifier = ref.read(authControllerProvider.notifier);

  final Uint8List? bytes;
  try {
    bytes = await useCase.pickAndProcess(source: MediaSource.gallery, context: context);
  } catch (e) {
    if (context.mounted) _avatarSnack(context, '${l10n.meAvatarSaveFailed}（选图/处理）: ${_briefErr(e)}');
    return;
  }
  if (bytes == null) return; // 取消 / 权限拒(已弹引导)

  final String url;
  try {
    final res = await useCase.uploadBytes(scope: MediaScope.public, bytes: bytes);
    url = res.publicUrl ?? res.objectKey;
  } catch (e) {
    if (context.mounted) _avatarSnack(context, '${l10n.meAvatarSaveFailed}（上传）: ${_briefErr(e)}');
    return;
  }

  try {
    final updated = await meRepository.updateAvatar(url);
    authNotifier.applyProfile(updated);
    if (context.mounted) _avatarSnack(context, l10n.meAvatarSaved);
  } catch (e) {
    if (context.mounted) _avatarSnack(context, '${l10n.meAvatarSaveFailed}（保存）: ${_briefErr(e)}');
  }
}

/// Dio 异常优先抽状态码/简短信息,避免堆栈刷屏。
String _briefErr(Object e) {
  if (e is DioException) {
    final code = e.response?.statusCode;
    return code != null ? 'HTTP $code' : (e.message ?? e.type.name);
  }
  return e.toString();
}

void _avatarSnack(BuildContext context, String msg) {
  if (!context.mounted) return;
  showAppToast(context, msg);
}
