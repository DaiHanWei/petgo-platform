import 'dart:convert';

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

  // --- Tailsonality 挽留弹窗「已弹过」的结果 token（V1.3.2 Story 3.2 · AC7.2）---
  //
  // 粒度是**结果 token**（不是宠物 / 账号）：重测出新结果会再有一次机会。按设备存，换设备再弹一次可接受（AD-3）。
  // 只保留最近 [kTailsonalityRetentionShownCap] 个，防无限增长。
  static const kTailsonalityRetentionShown = 'petgo.tailsonality_retention_shown';
  static const kTailsonalityRetentionShownCap = 50;

  bool tailsonalityRetentionShown(String token) =>
      (_prefs.getStringList(kTailsonalityRetentionShown) ?? const []).contains(token);

  Future<void> markTailsonalityRetentionShown(String token) {
    final list = [...?_prefs.getStringList(kTailsonalityRetentionShown)]..remove(token);
    list.add(token);
    final kept = list.length > kTailsonalityRetentionShownCap
        ? list.sublist(list.length - kTailsonalityRetentionShownCap)
        : list;
    return _prefs.setStringList(kTailsonalityRetentionShown, kept);
  }

  // --- 推荐更新「Nanti」冷却记录（V1.3.4 Story 3.2 · AD-11）---
  //
  // 记的是**被跳过的那个最新版本号 + 跳过时刻（UTC 毫秒）**：服务端最新版本上升即重新提示，
  // 否则 72h 内不再出横幅。强制更新不受此影响。按设备存，换设备重新提示可接受。
  static const kUpdateSkippedVersion = 'petgo.update_skipped_version';
  static const kUpdateSkippedAt = 'petgo.update_skipped_at';

  String? get updateSkippedVersion => _prefs.getString(kUpdateSkippedVersion);

  DateTime? get updateSkippedAt {
    final ms = _prefs.getInt(kUpdateSkippedAt);
    return ms == null ? null : DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
  }

  Future<void> setUpdateSkipped(String version, DateTime at) async {
    await _prefs.setString(kUpdateSkippedVersion, version);
    await _prefs.setInt(kUpdateSkippedAt, at.toUtc().millisecondsSinceEpoch);
  }

  // --- 运营弹窗本地频次（V1.3.4 Story 5.4 · AC3 · AD-15）---
  //
  // JSON 对象字符串 `{ "<popup public_token>": "once" | "yyyy-MM-dd"(WIB) }`。
  // 🔴 游客与登录用户**都写**：游客期间写下的 `once` 登录后照样剔除（PRD AC4）。
  // 按插入顺序保留最近 [kOpsPopupShownCap] 条，防无限增长。
  static const kOpsPopupShown = 'petgo.ops_popup_shown';
  static const kOpsPopupShownCap = 200;

  /// 缺失 / 损坏（非 JSON、非对象）→ 空 Map，不抛；非字符串值的条目丢弃。
  Map<String, String> opsPopupShown() {
    final raw = _prefs.getString(kOpsPopupShown);
    if (raw == null) return <String, String>{};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return <String, String>{};
      return <String, String>{
        for (final e in decoded.entries)
          if (e.key is String && e.value is String) e.key as String: e.value as String,
      };
    } catch (_) {
      return <String, String>{};
    }
  }

  /// 覆盖同 token 旧值并移到末尾；超过 [kOpsPopupShownCap] 条丢最早的。
  Future<void> markOpsPopupShown(String token, String value) {
    final map = opsPopupShown()..remove(token);
    map[token] = value;
    final entries = map.entries.toList();
    final kept = entries.length > kOpsPopupShownCap
        ? entries.sublist(entries.length - kOpsPopupShownCap)
        : entries;
    return _prefs.setString(kOpsPopupShown, jsonEncode(Map<String, String>.fromEntries(kept)));
  }

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
