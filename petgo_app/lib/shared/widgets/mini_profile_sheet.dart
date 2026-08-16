import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/analytics/analytics.dart';
import '../../core/network/problem_detail.dart';
import '../../core/theme/colors.dart';
import '../../core/theme/elevation.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/auth/domain/auth_guard.dart';
import '../../features/auth/domain/auth_state.dart';
import '../../features/content/data/mini_profile_repository.dart';
import '../../features/social/data/blocked_users_repository.dart';
import '../../features/social/domain/account_action_entry.dart';
import '../../features/social/presentation/account_report_sheet.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'app_toast.dart';
import 'confirm_sheet.dart';

/// 他人迷你主页预览卡（Story 3.8，FR-26）。点他人头像/昵称从底部弹卡。
///
/// 含头像+昵称、发布数、签名或「主页筹备中」措辞（**非技术性表达**）、关闭按钮；
/// **无「关注」「查看主页」按钮**。已注销用户（isDeactivated）**不弹卡**（NFR-8）。
///
/// **V1.1.4 Story 1.2（FR-94）**：右上角加「⋯」菜单 → 拉黑二次确认。三条既有行为同时调整：
/// 1. 拉取失败由「静默不弹」改成 **Toast 提示**（原注释理由「非关键路径」在有了拉黑入口后不再成立：
///    用户按了没反应，会以为是自己没点到）；
/// 2. **已主动拉黑**（服务端 403 `blocked-user`）走**独立分支**，与网络失败分开提示；
/// 3. **已注销仍旧不弹卡、且不给任何提示**（NFR-8，一字不改）。
///
/// [entry]：这张卡是**从哪儿点开的** —— Feed / 详情页作者是 {@link AccountActionEntry.miniProfile}，
/// 评论区作者是 {@link AccountActionEntry.comment}。只用于埋点，不影响任何行为。
/// 黑名单页那个入口的点击量要单独看（见 `AccountActionEntry` 的说明）。
///
/// [onBlocked] / [onReported]：拉黑 / 举报成功且两层弹层都收起后回调 ——
/// 让当前这一屏立刻跟上（移除该作者的卡片、详情侧退回列表）。
/// **仅成功路径触发**；取消、失败都不触发。两者收尾动作相同，调用方一般传同一个回调。
Future<void> showMiniProfile(BuildContext context, WidgetRef ref, int userId,
    {VoidCallback? onBlocked,
    VoidCallback? onReported,
    AccountActionEntry entry = AccountActionEntry.miniProfile}) async {
  final MiniProfile profile;
  try {
    profile = await ref.read(miniProfileRepositoryProvider).getMiniProfile(userId);
  } catch (e) {
    // ⚠️ Story 1.2 AC3 改的就是这个分支本身（原为 `catch (_) { return; }` 静默返回）。
    // 不要在旁边另加一条失败分支——那样原路径照样静默，用户还是什么都看不到。
    if (!context.mounted) return;
    final l10n = AppLocalizations.of(context);
    final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
    // 「已拉黑」是**独立分支**，判据是 typeSlug（Story 1.1 AC6：403 + type .../blocked-user）。
    // 混进网络失败的话，用户会一直重试一个永远不会成功的动作。
    // 文案走本地化键，**不展示 ProblemDetail 的 detail 原文**（CLAUDE.md 约束）。
    showAppToast(
      context,
      problem?.typeSlug == 'blocked-user' ? l10n.miniProfileBlocked : l10n.miniProfileLoadFailed,
    );
    return;
  }
  if (profile.isDeactivated) return; // 已注销：不触发迷你卡，也**不给任何提示**（NFR-8）
  if (!context.mounted) return;
  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.surface,
    showDragHandle: true,
    builder: (_) => _MiniProfileCard(
      profile: profile,
      userId: userId,
      ref: ref,
      onBlocked: onBlocked,
      onReported: onReported,
      entry: entry,
    ),
  );
}

class _MiniProfileCard extends StatelessWidget {
  const _MiniProfileCard({
    required this.profile,
    required this.userId,
    required this.ref,
    required this.onBlocked,
    required this.onReported,
    required this.entry,
  });

  final MiniProfile profile;
  final int userId;
  final WidgetRef ref;
  final VoidCallback? onBlocked;
  final VoidCallback? onReported;

  /// 这张卡从哪儿点开的（仅埋点用）。
  final AccountActionEntry entry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final avatar = profile.avatarUrl;
    // 目标是自己 → 「⋯」整体不渲染（不是渲染后禁用，也不是点了报错）。
    // 游客照常渲染：菜单里的动作各自走 FR-0C 登录门控（与 `openReport` 同一惯例）。
    final currentUserId = ref.read(authControllerProvider).profile?.id;
    final showMore = currentUserId != userId;
    // ⚠️ `width: double.infinity` 不可省（bug 2026-08-07：卡片只有半屏宽）。
    //
    // 根因在 Material 的 BottomSheet：M3 默认 `constraints = maxWidth 640`**非空**，于是
    // `BottomSheet.build` 恒把内容包进 `Align(alignment: bottomCenter)` —— 而 Align 会把
    // 宽度约束**放松**（loosen）后再传给子树。约束一松，这张卡就按内容的固有宽度收缩，
    // 宽度变成「最宽的那个子元素」= 那行个性签名的文字宽度（签名越短卡越窄）。
    // 屏宽 640dp 以下都命中，与设备无关，不是模拟器的问题。
    //
    // 本仓其余 20+ 处底部弹层都在最外层显式撑满（见 `confirm_sheet.dart` 的同款写法），
    // 只有本文件漏了。新增弹层照抄这一层，别指望 sheet 自己会满宽。
    return SizedBox(
      width: double.infinity,
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.sm, AppSpacing.xl, AppSpacing.xl),
          // ⚠️ `fit: StackFit.passthrough` 不可换成默认 `StackFit.loose`：默认值会把外层的
          // 紧宽度约束**放松**后再传给 Column，卡片当场缩回「最宽子元素」的宽度——
          // 正是上面那段注释里修过的半屏 bug 换个地方复发，而宽度回归用例量的是
          // 更外层的 `SingleChildScrollView`，**照样绿**。
          child: Stack(
            fit: StackFit.passthrough,
            children: [
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircleAvatar(
                    radius: 32,
                    backgroundColor: AppColors.border,
                    backgroundImage: AppImage.provider(avatar, thumbWidth: 200),
                    child: (avatar == null || avatar.isEmpty)
                        ? const Icon(Icons.person_rounded, size: 32, color: AppColors.textTertiary)
                        : null,
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(profile.nickname ?? '', style: AppTypography.title),
                  const SizedBox(height: AppSpacing.xs),
                  Text(l10n.miniProfilePostCount(profile.postCount), style: AppTypography.caption),
                  const SizedBox(height: AppSpacing.md),
                  // 有签名就展示签名，没有才退回「主页筹备中」占位（2026-08-07 用户反馈）。
                  //
                  // 为什么是二选一而不是两句都显示：占位文案的存在意义就是「这里暂时没内容可看」，
                  // 签名一旦在场，这句话既多余又自相矛盾（明明有东西看）。
                  //
                  // ⚠️ 签名是用户自填自由文本，骚扰账号可以把攻击性内容写在这里——而这正是你要
                  // 举报他的那张卡。**举报场景下不隐藏签名**（隐藏会让点开「⋯」时内容跳动）。
                  if (profile.hasSignature)
                    Text(
                      profile.signature!.trim(),
                      key: const ValueKey('miniProfileSignature'),
                      style: AppTypography.body.copyWith(color: AppColors.textSecondary),
                      textAlign: TextAlign.center,
                    )
                  else
                    Text(
                      l10n.miniProfileComingSoon,
                      key: const ValueKey('miniProfileComingSoon'),
                      style: AppTypography.body.copyWith(color: AppColors.textSecondary),
                      textAlign: TextAlign.center,
                    ),
                  const SizedBox(height: AppSpacing.lg),
                  TextButton(
                    key: const ValueKey('miniProfileClose'),
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(l10n.commonClose),
                  ),
                ],
              ),
              if (showMore)
                Positioned(
                  top: 0,
                  right: 0,
                  child: Builder(
                    // Builder：菜单要按「⋯」自己的 RenderBox 定位，需要它所在那层的 context。
                    builder: (buttonContext) => IconButton(
                      key: const ValueKey('miniProfileMore'),
                      icon: const Icon(Icons.more_horiz_rounded,
                          size: 22, color: AppColors.textSecondary),
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints.tightFor(width: 36, height: 36),
                      onPressed: () => _openCardMenu(buttonContext, context, l10n),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// 「⋯」菜单：**锚在卡片右上角向上弹**的浮层（决策 C-76）。
  ///
  /// ⚠️ 不是第三层底部抽屉、也不向下弹——向下弹会溢出屏幕底部并盖住头像（C-76 已否决）。
  /// ⚠️ UI 稿 A2 的框外说明写「锚在「⋯」下方」，那是把已否决方案写进了说明，**别照着做**。
  /// 浮层挂 root Overlay（在 modal sheet 之上），**卡片内容不省略、不压暗**：遮罩层用全透明
  /// GestureDetector 仅接管「点外部关闭」，不铺任何底色。
  void _openCardMenu(BuildContext buttonContext, BuildContext cardContext, AppLocalizations l10n) {
    final button = buttonContext.findRenderObject() as RenderBox?;
    final overlay = Overlay.maybeOf(buttonContext, rootOverlay: true);
    if (button == null || overlay == null || !button.hasSize) return;
    final overlayBox = overlay.context.findRenderObject() as RenderBox?;
    if (overlayBox == null) return;

    final topLeft = button.localToGlobal(Offset.zero, ancestor: overlayBox);
    final rightInset = overlayBox.size.width - (topLeft.dx + button.size.width);
    // 用 bottom 定位 = 菜单**底边**贴着「⋯」上沿，向上生长：菜单再长也不会往屏幕下方溢出。
    final bottomInset = overlayBox.size.height - topLeft.dy + 6;

    late final OverlayEntry entry;
    void close() => entry.remove();

    entry = OverlayEntry(
      builder: (_) => Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              key: const ValueKey('miniProfileMenuScrim'),
              behavior: HitTestBehavior.opaque,
              onTap: close,
              // 透明遮罩：只吃点击，不压暗卡片（AC1）。
              child: const SizedBox.expand(),
            ),
          ),
          Positioned(
            right: rightInset,
            bottom: bottomInset,
            child: Material(
              color: AppColors.surface,
              elevation: AppElevation.overlay,
              borderRadius: BorderRadius.circular(14),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 260),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // 「举报」与「拉黑」**并列、不分主次**：两者都不用红字强调，副标题各说各的用途。
                    //
                    // ⚠️ 已举报过 → 文案换成「已举报 / 点击可再次举报」并用品牌紫，
                    // **必须读起来像「还能再点」而不是禁用态**：再报一次是有意义的
                    // （每次的类型独立留存，第一次报骚扰、第二次报仿冒正是问题在升级的证据）。
                    _menuItem(
                      key: const ValueKey('miniProfileMenuReport'),
                      emoji: profile.reported ? '📌' : '🚩',
                      label: profile.reported ? l10n.accountReportedAction : l10n.accountReportAction,
                      subtitle: profile.reported
                          ? l10n.accountReportedActionSub
                          : l10n.accountReportActionSub,
                      labelColor: profile.reported ? AppColors.mint : AppColors.ink,
                      onTap: () {
                        close();
                        _startReport(cardContext);
                      },
                    ),
                    // ⚠️ 举报之后**拉黑项照常可点、不置灰不隐藏**：拉黑带来一个举报没有的效果 ——
                    // 从此进不去对方主页。以「已举报」为由禁掉它是错的。
                    _menuItem(
                      key: const ValueKey('miniProfileMenuBlock'),
                      emoji: '🚫',
                      label: l10n.blockUserAction,
                      subtitle: l10n.blockUserActionSub,
                      onTap: () {
                        close();
                        _startBlock(cardContext, l10n);
                      },
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
    overlay.insert(entry);
  }

  /// 菜单项：emoji + 主文案 14/w600 + 副标题 12（视觉规格取自 `content_detail_page` 的「···」抽屉）。
  Widget _menuItem({
    required Key key,
    required String emoji,
    required String label,
    required String subtitle,
    required VoidCallback onTap,
    Color labelColor = AppColors.ink,
  }) =>
      InkWell(
        key: key,
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(emoji, style: const TextStyle(fontSize: 16)),
              const SizedBox(width: 10),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(label,
                        style: TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w600, color: labelColor)),
                    const SizedBox(height: 2),
                    Text(subtitle,
                        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );

  /// 举报：打开账号举报抽屉；成功后用户点「关闭」→ **两层一并收起**（回到点开迷你卡之前的页面）。
  ///
  /// 中途取消（没提交就点取消）只收起抽屉那一层，回到迷你卡本身。
  Future<void> _startReport(BuildContext cardContext) async {
    // FR-0C：游客点社区动作 → 强登录引导，不发请求（与拉黑同一门控）。
    if (!requireLogin(ref, cardContext, onAllowed: () {})) return;
    final submitted = await openAccountReport(
      cardContext,
      ref,
      userId,
      // 「已举报」来自服务端标记（Story 2.1 AC8），不是前端会话态。
      alreadyReported: profile.reported,
      entry: entry,
    );
    if (!submitted) return;
    if (cardContext.mounted) {
      Navigator.of(cardContext).pop(); // 收起迷你卡（举报抽屉已自行收起）
    }
    // 两层收起后再通知调用方，让当前这一屏立刻不再有他的内容（Story 2.3）。
    // ⚠️ **静默**：这里不给任何提示，提示会泄露「举报会隐藏内容」。
    onReported?.call();
  }

  /// 拉黑二次确认 → 提交 → 成功收起两层 / 失败保持打开（AC2，C1–C4）。
  ///
  /// 成功收起、失败保持打开，两者**行为相反是刻意的**：失败不该让用户重新走一遍入口。
  Future<void> _startBlock(BuildContext cardContext, AppLocalizations l10n) async {
    // FR-0C：游客点社区动作 → 强登录引导，不发请求（与 `openReport` 同一门控）。
    if (!requireLogin(ref, cardContext, onAllowed: () {})) return;
    // toast 要在两层弹层收起**之后**给，那时 cardContext 已失效 → 先把 root Overlay 拿在手里。
    final overlay = Overlay.maybeOf(cardContext, rootOverlay: true);
    final ok = await showConfirmSheet(
      cardContext,
      title: l10n.blockUserTitle(profile.nickname ?? ''),
      // 只说「不再看到 TA 的内容和评论」——**刻意不提影子评论、不提「对方不会收到通知」**（A-A27）。
      message: l10n.blockUserMessage,
      confirmLabel: l10n.blockUserAction,
      cancelLabel: l10n.commonCancel,
      danger: true, // → 主按钮 AppColors.popRed
      // 头像重复出现：**确认拉黑谁比确认动作本身更重要**（C1，刻意的冗余）。
      leading: _avatar(30, iconSize: 28),
      confirmKey: const ValueKey('confirmBlockUser'),
      onConfirm: () async {
        try {
          await ref.read(blockedUsersRepositoryProvider).block(userId);
          // ⚠️ 埋点在**成功之后**（V1.1.2 的教训：门控前就上报会让指标系统性高估）。
          // 拉黑失败不上报，取消也不上报。
          // origin=BLOCK 与举报自动产生的隐藏（origin=REPORT）分开 ——
          // 不分来源的话，隐藏关系总量会被举报量灌大，看不出主动拉黑的真实使用情况。
          Analytics.capture('social_user_hide_submitted', {
            'origin': 'BLOCK',
            'entry': entry.wire,
          });
          return true;
        } catch (_) {
          // 失败提示必须在这里给：此时确认抽屉仍然开着，`showConfirmSheet` 尚未返回。
          // ⚠️ `top: true` 与举报失败（Story 2.2 AC7）保持同一口径：抽屉还开着时，
          // toast 的默认底部位置正好压在按钮区上，用户会以为提示是按钮的一部分。
          if (overlay != null) {
            showAppToastOnOverlay(overlay, l10n.blockUserFailed, top: true);
          }
          return false;
        }
      },
    );
    if (!ok) return;
    if (cardContext.mounted) Navigator.of(cardContext).pop(); // 收起迷你卡（确认抽屉已自行收起）
    if (overlay != null) showAppToastOnOverlay(overlay, l10n.blockUserSuccess);
    onBlocked?.call();
  }

  Widget _avatar(double radius, {required double iconSize}) {
    final avatar = profile.avatarUrl;
    return CircleAvatar(
      radius: radius,
      backgroundColor: AppColors.border,
      backgroundImage: AppImage.provider(avatar, thumbWidth: 200),
      child: (avatar == null || avatar.isEmpty)
          ? Icon(Icons.person_rounded, size: iconSize, color: AppColors.textTertiary)
          : null,
    );
  }
}
