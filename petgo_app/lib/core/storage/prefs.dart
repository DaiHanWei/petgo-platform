import 'package:shared_preferences/shared_preferences.dart';

/// 非敏感偏好存储（语言 / petStatus / 引导计数等）。
///
/// 架构约定：非敏感偏好走 [SharedPreferences]，令牌走 secure_storage。
class AppPrefs {
  AppPrefs(this._prefs);

  final SharedPreferences _prefs;

  static Future<AppPrefs> create() async {
    final prefs = AppPrefs(await SharedPreferences.getInstance());
    await prefs._pruneRemovedKeys(); // 清掉已废弃的键（见文件末尾说明）
    return prefs;
  }

  // --- 语言 ---
  static const _kLocale = 'petgo.locale';
  String? get localeCode => _prefs.getString(_kLocale);
  Future<void> setLocaleCode(String code) => _prefs.setString(_kLocale, code);

  // --- 宠物状态（Story 1.6 写）---
  static const _kPetStatus = 'petgo.pet_status';
  String? get petStatus => _prefs.getString(_kPetStatus);
  Future<void> setPetStatus(String status) => _prefs.setString(_kPetStatus, status);

  // --- 档案提示条计数（Story 1.7）---

  // --- 推送权限是否已申请过（Story 6.4，拒绝后不再主动弹）---
  static const _kPushPermissionAsked = 'petgo.push_permission_asked';
  bool get pushPermissionAsked => _prefs.getBool(_kPushPermissionAsked) ?? false;
  Future<void> setPushPermissionAsked(bool asked) => _prefs.setBool(_kPushPermissionAsked, asked);

  // --- 上一次已知的推送授权态（Story 9.3，推送疲劳终点信号）---
  //
  // 🔴 只为算出「撤销」这一个跃迁：系统权限的 granted→denied 通常发生在 App
  // 被杀掉、用户在系统设置里关掉通知的时候 —— 端上唯一能察觉的时机是下次冷启动，
  // 而那时内存里没有任何「之前是开着的」记忆。所以必须落盘。
  // ⚠️ 存的是布尔态，不是权限本身；真相源永远是系统权限查询。
  static const _kPushPermissionLastGranted = 'petgo.push_permission_last_granted';
  bool? get pushPermissionLastGranted => _prefs.getBool(_kPushPermissionLastGranted);
  Future<void> setPushPermissionLastGranted(bool granted) =>
      _prefs.setBool(_kPushPermissionLastGranted, granted);
  // --- FR-85 三个用户侧触发点各自的「已提示过」标记（V1.1.6 Story 8.2）---
  //
  // 🛡 **AD-14 Rule 2：四键物理隔离** —— 这三个 + 手机号软引导（Story 7.2）那一个，
  //    禁止任何两者共用。混用一个键 = 一个触发点用掉全部机会，
  //    FR-85 直接退化成「只提醒一次」。
  // 🛡 **AD-14 Rule 3**：它们与上面的 `petgo.push_permission_asked`（第二代首启申请）
  //    **互不相干** —— 存量用户那个键为 true 时，这三个仍从「未触发」开始，
  //    否则 FR-85 对存量用户完全失效，而那正是这条 FR 要解决的问题。
  static const kPushPromptFirstConsult = 'petgo.push_prompt_first_consult';
  static const kPushPromptProfileCreated = 'petgo.push_prompt_profile_created';
  static const kPushPromptNotificationCenter = 'petgo.push_prompt_notification_center';

  // --- 手机号软引导「已提示过」标记（V1.1.6 Story 7.2 · FR-70）---
  //
  // 🛡 **AD-14 Rule 2 四键中的第四把** —— 与上面推送权限那三把**物理隔离**。
  //    PRD 明确：手机号采集「全局仅一次」，不跟随 FR-85 的多触发点模型；
  //    共用任何一把都会让两条功能互相消耗机会。
  // ⚠️ 按设备存储 ⇒ 「全局仅一次」实为「**本设备**仅一次」，换设备会再问一次。
  //    这是 AD-14 Rule 1 的既定代价、不是缺陷；已填手机号者永不被问，故影响面很小。
  static const kPhonePromptShown = 'petgo.phone_prompt_shown';

  // --- 已废弃的键（下面这些**刻意不再提供 getter/setter**）---
  //
  // `petgo.splash_last_shown_date`：曾用于「splash 当天只播一次完整动画」。
  // 该门控已在 V1.1.2 Story 7.3（决策 C-3）**整条废止** —— 现在每次冷启动都播，
  // reduce-motion 直落终态。原先这里只留了一句描述已删功能的孤立注释、下面却没有任何成员
  // （code-review 2026-08-04 指出会误导后来人以为门控仍在）。
  //
  // 存量安装里这个键会一直留着。启动时**一次性清掉**，避免脏数据长期堆在 prefs 里
  // （无副作用：全仓已无任何读取点）。
  static const _kRemovedKeys = <String>['petgo.splash_last_shown_date'];

  /// 清理已废弃的键。由 [create] 在初始化后调用一次，失败无所谓（纯清理）。
  Future<void> _pruneRemovedKeys() async {
    for (final k in _kRemovedKeys) {
      if (_prefs.containsKey(k)) await _prefs.remove(k);
    }
  }

  int getInt(String key, {int fallback = 0}) => _prefs.getInt(key) ?? fallback;
  Future<void> setInt(String key, int value) => _prefs.setInt(key, value);
  bool getBool(String key, {bool fallback = false}) => _prefs.getBool(key) ?? fallback;
  Future<void> setBool(String key, bool value) => _prefs.setBool(key, value);
}
