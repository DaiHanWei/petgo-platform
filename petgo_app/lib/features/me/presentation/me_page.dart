import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/customer_service_sheet.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/widgets/design/baru_badge.dart';
import '../../../shared/widgets/post_cover.dart';
import '../../auth/data/me_repository.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_response.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/pet_age.dart';
import '../../profile/domain/pet_profile.dart';
import '../data/my_posts_repository.dart';

/// 「我的」页面（Story 7.1，FR-20 · F8 信息架构重组）。
///
/// 🔄 PRD V1.0.0 修订（F8 · 2026-06-08）：顶栏右上「帮助反馈」+「设置」双图标；语言/退出/注销三项
/// 收进二级设置页（点设置图标进入）；主页面主体只承载「人 + 宠物」内容——用户信息 / 宠物卡片或引导卡
/// （AC5 三态，人 60%/宠物 40%）/ 宠物状态 / 我的发布。语言(7.2)/退出注销(7.3) 仅放入口。
class MePage extends ConsumerWidget {
  const MePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);
    final profile = auth.profile;
    return Scaffold(
      backgroundColor: AppColors.base,
      // 原型 profil.html：无大标题，右上仅 headset + gear 双 ibtn（白底圆角+阴影）。
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        // 左上角品牌标：与首页同一 Tailtopia wordmark（bug 20260702-206：用 logo 替代文字标题）。
        centerTitle: false,
        titleSpacing: 20,
        title: SvgPicture.asset(
          'assets/brand/logo.svg',
          height: 28,
          semanticsLabel: l10n.appTitle,
        ),
        actions: [
          // 帮助反馈图标（PDP 数据主体权利可达路径承载之一）。
          _IconBtn(
            valueKey: 'meHelp',
            icon: Icons.support_agent_outlined,
            tooltip: l10n.meHelp,
            onTap: () => showCustomerServiceSheet(context),
          ),
          const SizedBox(width: 8),
          // 设置图标 → 二级设置页（语言/退出/注销）。PDP 注销入口经此可达。
          _IconBtn(
            valueKey: 'meSettings',
            icon: Icons.settings_outlined,
            tooltip: l10n.meSettings,
            onTap: () => context.push('/me/settings'),
          ),
          const SizedBox(width: AppSpacing.screenEdge),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          // ① profhead：用户信息 + 宠物 mini 同卡（原型 p-profil profhead）。
          _ProfileHeadCard(
            profile: profile,
            onEdit: () => _editProfile(context, ref),
            onAvatarTap: () => _changeAvatar(context, ref),
          ),
          const SizedBox(height: AppSpacing.lg),
          // AKTIVITAS 入口组（0711 profil-entries）：订单为主入口（PawCoin 已并入订单列表 header
          // 余额格，退款经订单详情可达），紫虚线卡 + BARU 徽章突出 V1.1 新增。
          _ActivitySection(onOrders: () => context.push('/me/orders')),
          const SizedBox(height: AppSpacing.lg),
          // ② 我的发布（原型 Postinganku）：小标题 + 裸 2 列网格（无卡边框）。
          // 注：宠物状态/改状态/编辑档案区块已按设计（原型 p-profil 无此块）移除。
          Padding(
            padding: const EdgeInsets.only(
              left: AppSpacing.xs,
              bottom: AppSpacing.sm,
            ),
            child: Text(
              l10n.meMyPostsTitle.toUpperCase(),
              style: AppTypography.caption.copyWith(
                letterSpacing: 0.6,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          _MyPostsList(),
        ],
      ),
    );
  }

  /// 编辑资料底抽屉（原型 profil-edit-sheet）：头像区（展示 + Ganti Foto 占位）+ 昵称可编辑 + 邮箱只读 + 保存/取消。
  ///
  /// 决策 #6（2026-06-18）：头像上传触媒体流较复杂，本期降级——保留头像展示 + 「Ganti Foto」入口提示「待接入」，
  /// 不阻塞 sheet 视觉还原；仅昵称走 updateNickname 落库。
  Future<void> _editProfile(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final profile = ref.read(authControllerProvider).profile;
    final controller = TextEditingController(
      text: profile?.nickname ?? profile?.displayName ?? '',
    );
    final newName = await showModalBottomSheet<String>(
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
            // 头像区（展示 + Ganti Foto 占位，头像上传本期降级）。
            Center(
              child: Column(
                children: [
                  _InitialAvatar(
                    avatarUrl: profile?.avatarUrl,
                    nickname: profile?.nickname ?? '',
                    radius: 38,
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    key: const ValueKey('meEditPhoto'),
                    onPressed: () {
                      showAppToast(ctx, l10n.helpComingSoon);
                    },
                    child: Text(
                      l10n.meEditPhotoChange,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.mint,
                      ),
                    ),
                  ),
                ],
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
              onPressed: () => Navigator.of(ctx).pop(controller.text.trim()),
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
    if (newName == null || newName.isEmpty || !context.mounted) return;
    try {
      final updated = await ref
          .read(meRepositoryProvider)
          .updateNickname(newName);
      ref.read(authControllerProvider.notifier).applyProfile(updated);
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
  Future<void> _changeAvatar(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final useCase = ref.read(mediaUploadUseCaseProvider);

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
      final updated = await ref.read(meRepositoryProvider).updateAvatar(url);
      ref.read(authControllerProvider.notifier).applyProfile(updated);
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
}

/// AC5 宠物区位三态分支（人 60%/宠物 40%）。
///
/// - 状态 A 且已建档 → [_PetCard]（宠物头像 + 名字 + 最近一条快乐时刻首图），点击跳成长档案 Tab。
/// - 状态 A 未建档 → [_PetGuideCard]「给你的宠物创建专属档案」，点击进 FR-11 创建流程。
/// - 状态 B / C → 不显示（宠物卡片与引导卡均不渲染）。
class _PetZone extends ConsumerWidget {
  const _PetZone();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(authControllerProvider).profile;
    // PLANNING/ENTHUSIAST 不显示
    if (profile?.petStatus != 'HAS_PET') return const SizedBox.shrink();
    // 「已建档」以真实档案为准，不信任登录响应里可能 stale 的 hasPetProfile：
    // 后端 /auth/* 登录响应恒返回 hasPetProfile=false，仅 /me 与 /pet-profiles 才是权威，
    // 否则老用户登录后即便有档案，/me 仍会错误显示「创建宠物档案」引导卡（用户反馈）。
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.when(
      data: (pet) => pet == null ? const _PetGuideCard() : const _PetCard(),
      loading: () => const SizedBox.shrink(),
      // 拉取失败不误导地催建档，留空待重试。
      error: (_, _) => const SizedBox.shrink(),
    );
  }
}

/// AC5「给你的宠物创建专属档案」引导卡（状态 A 未建档）。
class _PetGuideCard extends StatelessWidget {
  const _PetGuideCard();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return GestureDetector(
      key: const ValueKey('mePetGuideCard'),
      onTap: () => context.push('/profile/create'),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.accentGrowth.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: AppColors.accentGrowth.withValues(alpha: 0.3),
          ),
        ),
        child: Row(
          children: [
            const Icon(Icons.pets, color: AppColors.accentGrowth),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Text(
                l10n.mePetCardCreateTitle,
                style: AppTypography.body.copyWith(
                  color: AppColors.accentGrowth,
                ),
              ),
            ),
            const Icon(Icons.chevron_right, color: AppColors.accentGrowth),
          ],
        ),
      ),
    );
  }
}

/// AC5 宠物卡片（状态 A 已建档）·原型 petmini：宠物头像 + 名字 + 元数据（种类 · 年龄 · momen 数）+「Lihat →」。
class _PetCard extends ConsumerWidget {
  const _PetCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final petAsync = ref.watch(petProfileProvider);
    return petAsync.maybeWhen(
      data: (pet) {
        if (pet == null) return const SizedBox.shrink();
        final meta = _petMeta(context, ref, pet, l10n);
        return GestureDetector(
          key: const ValueKey('mePetCard'),
          onTap: () => context.go('/profile'), // 跳成长档案 Tab
          child: Container(
            padding: const EdgeInsets.all(11),
            decoration: BoxDecoration(
              color: AppColors.mintTint2, // 原型 petmini 紫浅底 #F8F6FF
              borderRadius: BorderRadius.circular(13),
            ),
            child: Row(
              children: [
                _InitialAvatar(
                  avatarUrl: pet.avatarUrl,
                  nickname: pet.name,
                  radius: 21,
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        pet.name,
                        style: AppTypography.body.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (meta.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          meta,
                          key: const ValueKey('mePetCardMeta'),
                          style: AppTypography.caption.copyWith(
                            color: AppColors.textSecondary,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Text(
                  '${l10n.meViewArchive} →',
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: AppColors.mint,
                  ),
                ),
              ],
            ),
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  /// 元数据「种类 · 年龄 · momen 数」：种类由 petType、年龄由 birthday 计算、momen 数取 archiveStats。
  String _petMeta(
    BuildContext context,
    WidgetRef ref,
    PetProfile pet,
    AppLocalizations l10n,
  ) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final momen = ref
        .watch(archiveStatsProvider)
        .asData
        ?.value
        .happyMomentCount;
    final age = computePetAge(pet.birthday);
    return [
      ?species,
      if (pet.birthday != null) l10n.growthArchiveAge(age.years, age.months),
      if (momen != null) l10n.meMomenCount(momen),
    ].join(' · ');
  }
}

/// 原型 .ibtn：38×38 白底圆角11 带阴影的图标按钮（profil 顶部 headset/gear）。
class _IconBtn extends StatelessWidget {
  const _IconBtn({
    required this.valueKey,
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  final String valueKey;
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        key: ValueKey(valueKey),
        onTap: onTap,
        borderRadius: BorderRadius.circular(11),
        child: Container(
          width: 38,
          height: 38,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(11),
            boxShadow: const [
              BoxShadow(
                color: Color(0x12162233),
                blurRadius: 8,
                offset: Offset(0, 2),
              ),
            ],
          ),
          child: Icon(icon, size: 18, color: AppColors.ink2),
        ),
      ),
    );
  }
}

/// AKTIVITAS 入口组（0711 profil-entries）：section label + BARU 徽章 + 紫虚线卡「Pesanan Saya」。
/// PawCoin 入口已迁至订单列表 header 余额格；退款经订单详情可达 —— 故本组仅一张订单主入口卡。
class _ActivitySection extends StatelessWidget {
  const _ActivitySection({required this.onOrders});

  final VoidCallback onOrders;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 分组标题 + BARU 红徽章（并排）。
        Padding(
          padding: const EdgeInsets.only(left: AppSpacing.xs, bottom: AppSpacing.sm),
          child: Row(
            children: [
              Text(
                l10n.meActivitySection,
                style: AppTypography.caption.copyWith(
                  letterSpacing: 0.6,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(width: 6),
              const BaruBadge(),
            ],
          ),
        ),
        // 紫虚线卡「Pesanan Saya · Termasuk saldo PawCoin」→ 订单中心。
        CustomPaint(
          foregroundPainter: DashedRRectPainter(
            color: AppColors.mint,
            radius: 14,
            dash: 5,
            gap: 4,
            strokeWidth: 2,
          ),
          child: Material(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(14),
            child: InkWell(
              key: const ValueKey('meOrders'),
              borderRadius: BorderRadius.circular(14),
              onTap: onOrders,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
                child: Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: AppColors.mintTint,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(Icons.credit_card_outlined,
                          size: 20, color: AppColors.mint),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(l10n.orderMyTitle,
                              style: const TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w600,
                                  color: AppColors.ink)),
                          const SizedBox(height: 2),
                          Text(l10n.meOrdersEntrySub,
                              style: AppTypography.caption
                                  .copyWith(color: AppColors.textSecondary)),
                        ],
                      ),
                    ),
                    const Icon(Icons.chevron_right, color: AppColors.muted),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// profhead 卡（原型 p-profil）：白卡 + 阴影，内含「用户行（渐变头像 + 名/邮箱 + Edit）」+
/// 状态 A 时下挂「宠物 mini」（[_PetZone]，紫浅底行）。B/C 仅用户行。
class _ProfileHeadCard extends StatelessWidget {
  const _ProfileHeadCard({required this.profile, required this.onEdit, this.onAvatarTap});

  final UserProfile? profile;
  final VoidCallback onEdit;
  final VoidCallback? onAvatarTap; // Story B：点头像换图(上传)

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = profile?.nickname ?? profile?.displayName ?? '';
    final email = profile?.email ?? '';
    final showPet = profile?.petStatus == 'HAS_PET';
    // 原型 profhead：背景与页面同为白，无可见卡片/阴影（白叠白 + 6% 阴影≈无），故不加卡片装饰；
    // 仅内部 petmini 用紫浅底区分。
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            // 原型 avlg 62（渐变 + 首字母）。Story B：点击换头像(上传) + 右下角相机角标。
            GestureDetector(
              key: const ValueKey('meAvatarUpload'),
              onTap: onAvatarTap,
              child: Stack(
                children: [
                  _InitialAvatar(avatarUrl: profile?.avatarUrl, nickname: name, radius: 31),
                  Positioned(
                    right: 0,
                    bottom: 0,
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(
                        color: AppColors.accentGrowth,
                        shape: BoxShape.circle,
                        border: Border.all(color: AppColors.surface, width: 2),
                      ),
                      child: const Icon(Icons.photo_camera, size: 12, color: Colors.white),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    name,
                    style: AppTypography.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (email.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      email,
                      style: AppTypography.caption,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            // 原型 editbtn：圆角矩形(8) + 1.5px 浅紫描边(#C2B0EC) + 紫字，无图标。
            OutlinedButton(
              key: const ValueKey('meEditNickname'),
              onPressed: onEdit,
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.accentGrowth,
                side: const BorderSide(
                  color: AppColors.dashedViolet,
                  width: 1.5,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
                // 原型 padding:6 12；给文字与描边留足内边距（去掉 compact 压缩）。
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 8,
                ),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(
                l10n.meEditButton,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
        // 宠物 mini（状态 A：已建档=宠物卡 / 未建档=引导卡；B/C 不挂）。
        if (showPet) ...[const SizedBox(height: 14), const _PetZone()],
      ],
    );
  }
}

/// 头像：有 URL 用网络图，否则彩色圆 + 昵称首字母（对齐设计稿 S17）。
class _InitialAvatar extends StatelessWidget {
  const _InitialAvatar({
    required this.avatarUrl,
    required this.nickname,
    required this.radius,
  });

  final String? avatarUrl;
  final String nickname;
  final double radius;

  @override
  Widget build(BuildContext context) {
    if (avatarUrl != null && avatarUrl!.isNotEmpty) {
      return CircleAvatar(
        // key 随 URL 变：换头像后 URL 变 → 强制重建,杜绝「provider 原地变更但旧图不重绘」。
        key: ValueKey('avatar-$avatarUrl'),
        radius: radius,
        backgroundImage: AppImage.provider(avatarUrl, thumbWidth: 240),
      );
    }
    final trimmed = nickname.trim();
    if (trimmed.isEmpty) {
      return CircleAvatar(
        radius: radius,
        backgroundColor: AppColors.divider,
        child: const Icon(Icons.person, color: AppColors.textTertiary),
      );
    }
    final initial = trimmed.characters.first.toUpperCase();
    // 原型 avlg：紫色渐变（135deg #845EC9 → 深紫）+ 白首字母。
    return Container(
      width: radius * 2,
      height: radius * 2,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint600],
        ),
      ),
      child: Text(
        initial,
        style: TextStyle(
          fontSize: radius * 0.8,
          fontWeight: FontWeight.w700,
          color: AppColors.onAccent,
        ),
      ),
    );
  }
}

/// 「我的发布」网格缩略图（原型 pthumb）：方形封面（无图→类型彩块）+ 左上角类型 badge。
class _MyPostCard extends StatelessWidget {
  const _MyPostCard({required this.post, required this.onTap});

  final MyPost post;
  final VoidCallback onTap;

  /// 类型 → (badge 文案, 文字色, 底色)：Momen 绿 / Tips 黄 / Cerita 紫（原型 b-happy/b-tips/b-story）。
  static (String, Color, Color) _badgeStyle(
    String type,
    AppLocalizations l10n,
  ) {
    switch (type) {
      case 'GROWTH_MOMENT':
        return (
          l10n.mePostTypeMomen,
          AppColors.momenBadgeText,
          AppColors.momenBadgeBg,
        );
      case 'KNOWLEDGE':
        return (
          l10n.mePostTypeTips,
          AppColors.tipsBadgeText,
          AppColors.goldTint,
        );
      default: // DAILY
        return (l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hasImage =
        post.firstImageUrl != null && post.firstImageUrl!.isNotEmpty;
    final (label, fg, bg) = _badgeStyle(post.type, l10n);
    return GestureDetector(
      key: ValueKey('myPost_${post.id}'),
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(11),
        child: Stack(
          fit: StackFit.expand,
          children: [
            hasImage
                ? AppImage.widget(
                    post.firstImageUrl!,
                    fit: BoxFit.cover,
                    thumbWidth: 400, // 「我的帖子」网格小图
                    errorBuilder: (context, error, stack) =>
                        PostCoverPlaceholder(type: post.type, emojiSize: 30),
                  )
                : PostCoverPlaceholder(type: post.type, emojiSize: 30),
            Positioned(
              top: 5,
              left: 5,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: bg,
                  borderRadius: BorderRadius.circular(5),
                ),
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w700,
                    color: fg,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MyPostsList extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final posts = ref.watch(myPostsProvider);
    return posts.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: SizedBox(height: 1),
      ),
      error: (_, _) => const SizedBox(height: 1),
      data: (items) {
        if (items.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Text(
              l10n.meNoPosts,
              key: const ValueKey('meNoPosts'),
              style: AppTypography.caption,
            ),
          );
        }
        // 2 列方形裸网格（原型 pgrid，无卡边框）：封面图（无图→彩块）+ 左上类型 badge；保留后端 created_at 倒序。
        return GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 7,
          crossAxisSpacing: 7,
          children: [
            for (final p in items)
              _MyPostCard(
                post: p,
                onTap: () => context.push('/content/${p.id}'),
              ),
          ],
        );
      },
    );
  }
}
