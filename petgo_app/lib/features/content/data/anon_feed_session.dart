import 'dart:math';

/// 游客首页的匿名会话 id（V1.1.6 Story 16.1 · AC4 / Story 16.3）。
///
/// 推荐序需要一个缓存键来放「这次刷新算出来的序列」，否则翻页会重复。登录用户用 userId，
/// 游客没有 —— 所以由客户端自己生成一个。
///
/// 🛡 **这不是身份标识，不做任何跟踪用途**：
/// - 只在**进程内**存活，冷启动即换一个新的（不落盘、不进 SecureStorage）
/// - 只随首页取数发出去，不附带在其他请求上
/// - 服务端只把它当缓存键的一段，不据此做任何授权判断
///
/// ⚠️ **曝光衰减对游客不生效**（无跨会话曝光记录）—— 这个 id 只解决「同一次会话内翻页重复」，
/// 不是为了记住游客看过什么。想让「已看过的不再反复出现」生效，得登录。
class AnonFeedSession {
  AnonFeedSession._();

  static String? _id;

  /// 本次进程的匿名会话 id（首次调用时生成）。
  ///
  /// 只用 `[a-z0-9]` —— 服务端还会再做一遍字符白名单与截断，这里先别给它送不合法的串。
  static String get id => _id ??= _generate();

  static String _generate() {
    final rnd = Random();
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    return List.generate(24, (_) => chars[rnd.nextInt(chars.length)]).join();
  }
}
