/// 成长档案的数据作用域（V1.1.6 Story 2.3）。
///
/// 同一套页面结构要服务两种人：
/// - **作者**看自己的档案 → 走 `/pet-profiles/me/*`（身份取自登录态）
/// - **访客**拿分享 token 看别人的宠物 → 走 `/public/shared-pets/{token}/*`
///
/// ## 🛡 为什么不是「给作者态查询加一个访问者参数」
/// 架构 AD-1 Rule 3 明令禁止那条路：一旦某个调用方漏传、或默认值写错，
/// **泄露是静默的** —— 没有任何测试会红，直到有人发现自己的健康记录出现在别人手机上。
/// 两条路径在服务端是**物理独立**的（访客那条结构上就取不到健康数据）；
/// 客户端这里只是决定「打哪个地址」，不承担任何过滤职责。
///
/// ⚠️ **客户端永远不要自己过滤访客不该看的东西** —— 该由服务端不下发。
/// 客户端过滤只是「看不见」，抓包照样拿得到。
class ArchiveScope {
  /// 作者看自己的档案。
  const ArchiveScope.me() : token = null;

  /// 访客拿分享 token 看别人的宠物。
  const ArchiveScope.visitor(String this.token);

  /// 分享 token；作者态为 null。
  final String? token;

  bool get isVisitor => token != null;

  @override
  bool operator ==(Object other) =>
      other is ArchiveScope && other.token == token;

  @override
  int get hashCode => token.hashCode;

  @override
  String toString() => isVisitor ? 'ArchiveScope.visitor($token)' : 'ArchiveScope.me()';
}
