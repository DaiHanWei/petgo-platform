/// 手机号脱敏展示（Story 7.2 · PRD §2.2，对齐 UI 稿 05b 屏）。
///
/// 规则：**保留前缀与末四位，中间遮蔽**（如 `+62 812•••••7890`）。
///
/// ⚠️ **这只是显示层。** 编辑抽屉里必须展示**完整号码**，否则用户改不了自己的号 ——
/// 服务端 `/me` 下发的本来就是完整号码（同一个人看自己的数据），
/// 脱敏放客户端做，口径只有这一处。
///
/// ⚠️ **不假设固定格式**：7-1 的校验刻意放宽（印尼人真实会写 `0812...`、`62812...`、
/// 带空格和横线的都有），所以这里也不能假设一定有 `+` 或固定段长，
/// 否则会在真实数据上输出乱码。
class PhoneMask {
  PhoneMask._();

  /// 末四位保留位数。
  static const _tailKeep = 4;

  /// 前缀保留位数：**国家码 2 + 运营商段 3**，对齐 UI 稿的 `+62 812-••••-7890`。
  static const _headKeep = 5;

  /// 短于这个长度就**原样返回**。
  /// `+1`：至少要能遮住一位才有意义 —— 遮不动就别遮，
  /// 输出 `+62 8121234` 这种"看着像脱敏其实没遮"的结果更糟。
  /// （用户看的是自己的号码，脱敏是防肩窥、不是防他自己。）
  static const _minLength = _headKeep + _tailKeep + 1;

  static String? mask(String? phone) {
    if (phone == null || phone.isEmpty) return phone;

    // 按**字符**处理，但只对数字计数：带空格/横线的输入不该影响末四位的判定。
    final digits = phone.replaceAll(RegExp(r'\D'), '');
    if (digits.length < _minLength) return phone;

    final head = digits.substring(0, _headKeep);
    final tail = digits.substring(digits.length - _tailKeep);
    final hidden = digits.length - _headKeep - _tailKeep;

    // 前缀按「国家码 + 运营商段」的读法插一个空格，与 UI 稿一致。
    final prettyHead = head.startsWith('62')
        ? '+${head.substring(0, 2)} ${head.substring(2)}'
        : head;
    return '$prettyHead${'•' * hidden}$tail';
  }
}
