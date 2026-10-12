import '../../profile/presentation/pet_insights_page.dart';
import '../../shop/domain/shop_product.dart';
import 'ops_target.dart';

/// 统一跳转目标 → 动作（V1.3.4 AD-16 · Story 5.2）。纯函数层，L0 逐行可测。
///
/// 值校验与后端 `OpsTargetPolicy.isWellFormed` 同口径（双端各拦一次）。
/// 执行层见 `presentation/open_ops_target.dart`。
sealed class OpsTargetAction {
  const OpsTargetAction();
}

/// 不跳转（NONE / 值非法）：只关闭，不报错。
final class OpsNoAction extends OpsTargetAction {
  const OpsNoAction();

  @override
  bool operator ==(Object other) => other is OpsNoAction;

  @override
  int get hashCode => (OpsNoAction).hashCode;
}

/// `go`：Tab 根（`/home` `/shop`）必须 go —— push 会二次构建 StatefulShellRoute，GlobalKey 撞车白屏
/// （见 `DeepLinkRoutes.shellTabRoots` 注释）。恒不需要登录。
final class OpsGoAction extends OpsTargetAction {
  const OpsGoAction(this.location);

  final String location;

  @override
  bool operator ==(Object other) => other is OpsGoAction && other.location == location;

  @override
  int get hashCode => Object.hash(OpsGoAction, location);

  @override
  String toString() => 'OpsGoAction($location)';
}

/// `push`：shell 外的顶层页面，保留返回栈。
final class OpsPushAction extends OpsTargetAction {
  const OpsPushAction(this.location, {this.requiresLogin = false});

  final String location;
  final bool requiresLogin;

  @override
  bool operator ==(Object other) =>
      other is OpsPushAction &&
      other.location == location &&
      other.requiresLogin == requiresLogin;

  @override
  int get hashCode => Object.hash(OpsPushAction, location, requiresLogin);

  @override
  String toString() => 'OpsPushAction($location, requiresLogin: $requiresLogin)';
}

/// 打开发布页（全屏 bottom sheet，不传 preset）。
final class OpsOpenPublishAction extends OpsTargetAction {
  const OpsOpenPublishAction({this.requiresLogin = true});

  final bool requiresLogin;

  @override
  bool operator ==(Object other) =>
      other is OpsOpenPublishAction && other.requiresLogin == requiresLogin;

  @override
  int get hashCode => Object.hash(OpsOpenPublishAction, requiresLogin);
}

/// 外部链接：App 内浏览器打开（系统默认配色，D-9），失败退系统浏览器。恒不需要登录。
final class OpsExternalAction extends OpsTargetAction {
  const OpsExternalAction(this.uri);

  final Uri uri;

  @override
  bool operator ==(Object other) => other is OpsExternalAction && other.uri == uri;

  @override
  int get hashCode => Object.hash(OpsExternalAction, uri);

  @override
  String toString() => 'OpsExternalAction($uri)';
}

/// 与后端 `OpsTargetPolicy.PRODUCT_TOKEN` 同口径。
final RegExp _productToken = RegExp(r'^[0-9A-Za-z_-]{1,32}$');

/// 与后端 `OpsTargetPolicy.MAX_VALUE_LENGTH`（`target_value VARCHAR(512)`）一致。
const int _maxExternalLength = 512;

/// 目标 → 动作。不认识 / 不合法 → [OpsNoAction]（PRD「目标为 NONE 或目标值无效 → 不跳转、不报错」）。
OpsTargetAction resolveOpsTarget(OpsTarget t) {
  final value = t.value;
  switch (t.type) {
    case OpsTargetType.none:
      return const OpsNoAction();
    case OpsTargetType.product:
      if (value == null || !_productToken.hasMatch(value)) return const OpsNoAction();
      return OpsPushAction('/shop/products/$value');
    case OpsTargetType.category:
      if (value == null || !ShopCategory.values.any((c) => c.api == value)) {
        return const OpsNoAction();
      }
      return OpsGoAction('/shop?category=$value');
    case OpsTargetType.appPage:
      return switch (OpsAppPage.fromWire(value)) {
        null => const OpsNoAction(),
        OpsAppPage.home => const OpsGoAction('/home'),
        OpsAppPage.publish => const OpsOpenPublishAction(requiresLogin: true),
        OpsAppPage.toko => const OpsGoAction('/shop'),
        OpsAppPage.knowYourPet =>
          const OpsPushAction(PetInsightsRoutes.hub, requiresLogin: true),
        OpsAppPage.health => const OpsPushAction('/profile/health', requiresLogin: true),
      };
    case OpsTargetType.external:
      final uri = _httpUri(value);
      return uri == null ? const OpsNoAction() : OpsExternalAction(uri);
  }
}

/// 只放 http / https（scheme 忽略大小写）且 host 非空；`javascript:` / `ftp:` / `https://` 一律拒。
Uri? _httpUri(String? value) {
  if (value == null || value.length > _maxExternalLength) return null;
  final uri = Uri.tryParse(value);
  if (uri == null) return null;
  final scheme = uri.scheme.toLowerCase();
  if (scheme != 'http' && scheme != 'https') return null;
  if (uri.host.isEmpty) return null;
  return uri;
}
