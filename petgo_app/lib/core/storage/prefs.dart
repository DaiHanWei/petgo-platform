import 'package:shared_preferences/shared_preferences.dart';

/// 非敏感偏好存储（语言 / petStatus / 引导计数等）。
///
/// 架构约定：非敏感偏好走 [SharedPreferences]，令牌走 secure_storage。
class AppPrefs {
  AppPrefs(this._prefs);

  final SharedPreferences _prefs;

  static Future<AppPrefs> create() async => AppPrefs(await SharedPreferences.getInstance());

  // --- 语言 ---
  static const _kLocale = 'petgo.locale';
  String? get localeCode => _prefs.getString(_kLocale);
  Future<void> setLocaleCode(String code) => _prefs.setString(_kLocale, code);

  // --- 宠物状态（Story 1.6 写）---
  static const _kPetStatus = 'petgo.pet_status';
  String? get petStatus => _prefs.getString(_kPetStatus);
  Future<void> setPetStatus(String status) => _prefs.setString(_kPetStatus, status);

  // --- 档案提示条计数（Story 1.7）---
  static const kProfilePromptRestartCount = 'petgo.profile_prompt_restart_count';
  static const kProfilePromptDismissedPermanently = 'petgo.profile_prompt_dismissed';
  static const kPetProfileCompleted = 'petgo.pet_profile_completed';

  int getInt(String key, {int fallback = 0}) => _prefs.getInt(key) ?? fallback;
  Future<void> setInt(String key, int value) => _prefs.setInt(key, value);
  bool getBool(String key, {bool fallback = false}) => _prefs.getBool(key) ?? fallback;
  Future<void> setBool(String key, bool value) => _prefs.setBool(key, value);
}
