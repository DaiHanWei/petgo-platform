/// 成长档案的数据作用域（V1.1.6 Story 2.3）。
///
/// 同一套页面结构要服务三种人：
/// - **作者**看自己的档案 → 走 `/pet-profiles/me/*`（身份取自登录态）
/// - **访客**拿分享 token 看别人的宠物 → 走 `/public/shared-pets/{token}/*`（游客可读）
/// - **站内访客**从别人主页点宠物卡进来 → 走 `/pets/{petId}/visitor/*`
///   （V1.3.0 batch-b1 Story 2.3 · AD-4 · **仅登录可用**）
///
/// ## 🔴 为什么站内入口另起一条地址，而不是把对方的分享 token 发下来
/// B1-D1 否掉了那个方案：分享 token 是**永久公开、可转发到站外**的，
/// 把它发给每个站内访客等于替对方做了一次公开分享。**站内可见 ≠ 可对外分发。**
/// 两条地址的**服务端投影是同一层**（AD-4 Rule 2），差的只是鉴权边界。
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
  const ArchiveScope.me()
      : token = null,
        petId = null;

  /// 访客拿分享 token 看别人的宠物（游客可读）。
  const ArchiveScope.visitor(String this.token) : petId = null;

  /// 站内访客：从别人主页的宠物卡点进来（仅登录可用，V1.3.0 batch-b1 Story 2.3）。
  const ArchiveScope.inAppVisitor(int this.petId) : token = null;

  /// 分享 token；作者态与站内访客态为 null。
  final String? token;

  /// 宠物 id；**只有站内访客态**有值（AD-4 Rule 1 明写按 petId 寻址）。
  final int? petId;

  /// 是不是「在看别人的宠物」—— 两种访客态都算。
  ///
  /// ⚠️ 判据是**两个都判**，不是只判 token：只判 token 的话站内态会被当成作者态，
  /// 于是去打 `/pet-profiles/me/*` —— 表现是「点别人的猫，看到的是自己的档案」。
  bool get isVisitor => token != null || petId != null;

  /// 站内入口进来的（据此隐藏「由 XX 分享」横幅 —— 站内点击不存在"谁分享的"这个语境）。
  bool get isInApp => petId != null;

  @override
  bool operator ==(Object other) =>
      other is ArchiveScope && other.token == token && other.petId == petId;

  @override
  int get hashCode => Object.hash(token, petId);

  @override
  String toString() {
    if (isInApp) return 'ArchiveScope.inAppVisitor($petId)';
    return token != null ? 'ArchiveScope.visitor($token)' : 'ArchiveScope.me()';
  }
}
