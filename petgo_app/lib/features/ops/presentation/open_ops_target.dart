import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/app_router.dart';
import '../../../core/router/route_intent.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_guide_controller.dart';
import '../../content/presentation/publish_compose_page.dart';
import '../../profile/data/current_pet_provider.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/presentation/widgets/pet_picker_sheet.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/ops_target.dart';
import '../domain/ops_target_action.dart';

/// 外链启动器（测试注入 fake）。返回 false = 没打开（url_launcher 的常见失败形态，不是抛异常）。
typedef OpsUrlLauncher = Future<bool> Function(Uri uri, LaunchMode mode);

Future<bool> _defaultLauncher(Uri uri, LaunchMode mode) => launchUrl(uri, mode: mode);

/// 游客被拦时的 `entrySource` / `login_guide_entry_blocked.entry`（运营弹窗与运营通知共用）。
const String opsTargetEntrySource = 'ops_target';

/// 统一跳转执行入口（V1.3.4 AD-16 · Story 5.2）。运营弹窗（5.4）与通知中心运营通知（6.3）
/// **直接调用本函数，不得各写一份跳转**。
///
/// 任何情况都不抛异常、不弹错误提示（PRD「目标为 NONE 或目标值无效 → 不跳转、不报错」）。
///
/// 🔴 导航与登录强弹窗一律用根 Navigator 的 context（[rootNavigatorKey]）：弹窗调用方可能已先
/// pop 自己，传入的 [context] 此时已卸载 —— 它**不参与**导航，也不做 `mounted` 判断（否则已登录用户
/// 点需登录页面会静默不跳）。保留该参数只为统一签名。
///
/// 🔴 **调用契约（[ref]）**：Riverpod 3 在宿主 widget 卸载后 `ref.read` 会抛 `StateError`。
/// 本函数只在**入口处同步**一次性读完所需 provider（登录态、登录引导协调器），之后不再碰 [ref]；
/// 因此调用方必须保证**调用当下** [ref] 仍挂载 —— 弹窗场景请传页面级 / 根级的 ref
/// （弹窗宿主页的 `WidgetRef`），或在关闭弹窗**之前**调用，不要传已随弹窗卸载的 ref。
Future<void> openOpsTarget(
  BuildContext context,
  WidgetRef ref,
  OpsTarget target, {
  OpsUrlLauncher? launcher,
}) async {
  try {
    final action = resolveOpsTarget(target);
    if (action is OpsNoAction) return;
    // 入口处一次性读完（见上「调用契约」）。
    final auth = ref.read(authControllerProvider);
    final loginGuide = ref.read(loginGuideControllerProvider);
    final nav = rootNavigatorKey.currentContext;
    if (nav == null || !nav.mounted) return;
    // 兽医端不承接用户侧页面（正常情况下兽医看不到运营弹窗 / 通知，这里是兜底）。
    if (auth.isVet) return;

    switch (action) {
      case OpsNoAction():
        return;
      case OpsGoAction(:final location):
        // Tab 根必须 go：push 会二次构建 StatefulShellRoute → GlobalKey 撞车白屏。
        nav.go(location);
      case OpsPushAction(:final location, :final requiresLogin):
        _runGated(auth, loginGuide, nav, requiresLogin, () {
          final c = rootNavigatorKey.currentContext;
          if (c != null && c.mounted) c.push(location);
        });
      case OpsOpenPublishAction(:final requiresLogin):
        _runGated(auth, loginGuide, nav, requiresLogin, () {
          final c = rootNavigatorKey.currentContext;
          if (c != null && c.mounted) PublishComposePage.open(c);
        });
      case OpsPetSpeciesAction(:final requiresLogin):
        // 入口处同步取根 ProviderContainer（调用契约：之后不再碰 ref）。登录后续跑时才刷新列表、切换当前宠物，
        // 用的都是这个长寿命容器——刷新的就是全 App 共享的 petListProvider，编辑页按同一份列表定位宠物。
        final container = ProviderScope.containerOf(nav, listen: false);
        _runGated(auth, loginGuide, nav, requiresLogin, () {
          openPetSpeciesLanding(
            () => container.refresh(petListProvider.future),
            (id) => container.read(currentPetIdProvider.notifier).select(id),
          );
        });
      case OpsExternalAction(:final uri):
        await _launchExternal(uri, launcher ?? _defaultLauncher);
    }
  } catch (_) {
    // 静默：运营跳转失败不打扰用户。
  }
}

/// 需登录且是游客 → 强弹窗 + pendingAction（用 `onResume` 而非 `location`：`RouteIntent.run`
/// 遇 location 走 `go`，会替换整个栈，shell 外顶层页没有返回栈）。不得直接 push 受控路由 ——
/// 路由守卫会把游客改写回 `/home`。
///
/// 与 `requireLogin`（`auth_guard.dart`）逐行同义：已登录直接执行；游客弹强弹窗并记 pendingAction。
/// 这里用入口处预读的 [auth] / [loginGuide] 而不是再传 ref，是为了让 ref 只在入口被读一次。
void _runGated(
  AuthState auth,
  LoginGuideController loginGuide,
  BuildContext nav,
  bool requiresLogin,
  void Function() run,
) {
  if (!requiresLogin || auth.isLoggedIn) {
    run();
    return;
  }
  loginGuide.showHardDialog(
    nav,
    pendingAction: RouteIntent(onResume: run),
    entrySource: opsTargetEntrySource,
  );
  // 与 app_shell.dart 同一规则：点击意图在门控之外记。
  Analytics.capture('login_guide_entry_blocked', {'entry': opsTargetEntrySource});
}

/// App 内浏览器（系统默认配色，不传任何颜色参数，D-9）→ 返回 false / 抛异常再试系统浏览器
/// （FR-137.3）→ 再失败静默。
///
/// ⚠️ 实际行为（url_launcher_android）：Custom Tabs 不可用时插件**自己**回退到内置 WebViewActivity
/// 并返回 true，所以 Android 上第二步通常走不到；第二步兜的是 iOS / 插件返回 false 或抛异常的情形。
Future<void> _launchExternal(Uri uri, OpsUrlLauncher launcher) async {
  bool opened;
  try {
    opened = await launcher(uri, LaunchMode.inAppBrowserView);
  } catch (_) {
    opened = false;
  }
  if (opened) return;
  try {
    await launcher(uri, LaunchMode.externalApplication);
  } catch (_) {
    // 静默。
  }
}

/// 「宠物类型」落地（V1.5.0 pet-species Story 3.5 · D-15 · UI E1）：刷新共享的「我的宠物」列表（不用可能过期的缓存）——
/// 0 只 → 建档页；1 只 → 切为当前宠物 → 编辑页；多只 → 选宠物抽屉（细分类型仍是 `OTHER` 的排最前，其余按创建顺序），
/// 选了同 1 只的处理，关掉抽屉什么都不做。只读宠物照常列出（进编辑页由现有只读态处理，Q-7）。
/// 任何失败静默（运营跳转不打扰用户）。导航一律用根 Navigator 的 context。
@visibleForTesting
Future<void> openPetSpeciesLanding(
    Future<List<PetListItem>> Function() loadPets, Future<void> Function(int) selectPet) async {
  try {
    final pets = await loadPets();
    final c = rootNavigatorKey.currentContext;
    if (c == null || !c.mounted) return;
    if (pets.isEmpty) {
      c.push('/profile/create');
      return;
    }
    int? id;
    if (pets.length == 1) {
      id = pets.single.id;
    } else {
      final ordered = petsForSpeciesLanding(pets);
      final l10n = AppLocalizations.of(c);
      final picked = await showPetPickerSheet(
        c,
        pets: ordered,
        title: l10n.petPickerChooseTitle,
        body: l10n.petSpeciesPushPickHint,
        multiSelect: false,
      );
      if (picked == null || picked.isEmpty) return;
      id = picked.first;
    }
    await selectPet(id);
    final c2 = rootNavigatorKey.currentContext;
    if (c2 != null && c2.mounted) c2.push('/profile/edit');
  } catch (_) {
    // 静默。
  }
}

/// 落地抽屉的顺序：细分类型**仍是** `OTHER` 的排最前（CIVET 等已细分的不算；旧后端无 species 时按大类 OTHER 算），
/// 其余保持接口顺序（创建顺序）。
List<PetListItem> petsForSpeciesLanding(List<PetListItem> pets) {
  bool stillOther(PetListItem p) => (p.species ?? p.petType) == 'OTHER';
  return [...pets.where(stillOther), ...pets.where((p) => !stillOther(p))];
}

