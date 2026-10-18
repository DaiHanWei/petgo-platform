import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/push_settings.dart';

/// 推送分类开关数据层（V1.4.0 ④ Story 1.3）。账号级，只存服务端（不做本地缓存）。
class PushSettingsRepository {
  PushSettingsRepository({required this.dio});

  final Dio dio;

  Future<PushSettings> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.mePushSettings);
    return PushSettings.fromJson(resp.data);
  }

  /// 切换一项（部分更新）；返回服务端确认后的完整设置。失败向上抛，由页面回弹 + Toast。
  Future<PushSettings> update(String code, bool enabled) async {
    final resp = await dio.patch<Map<String, dynamic>>(ApiPaths.mePushSettings, data: {
      'categories': {code: enabled},
    });
    return PushSettings.fromJson(resp.data);
  }
}

final pushSettingsRepositoryProvider =
    Provider<PushSettingsRepository>((ref) => PushSettingsRepository(dio: ref.read(dioProvider)));

/// 当前账号的推送分类设置。autoDispose：**每次进页都重拉**（PRD AC4：手机 A 改、手机 B 打开看到）。
/// 🔴 已登记 `resetUserScopedCaches`：换账号不得沿用上一账号的开关。
class PushSettingsNotifier extends AsyncNotifier<PushSettings> {
  @override
  Future<PushSettings> build() => ref.read(pushSettingsRepositoryProvider).fetch();

  void retry() => ref.invalidateSelf();

  /// 服务端确认某一项后写回（只改这一项，不覆盖其它行仍在途的乐观值）。
  void applyConfirmed(String code, bool value) {
    final current = state.value;
    if (current != null) state = AsyncData(current.copyWith(code, value));
  }
}

/// ⚠️ `retry: (_, _) => null`（同 bug 20261006-583）：Riverpod 3 默认对抛错的 provider 自动指数退避重试，
/// 重试期间状态是 `AsyncLoading` —— 断网时一直停在骨架，失败态要 40 多秒后才出来。失败就停在失败态，重试由用户触发。
final pushSettingsProvider = AsyncNotifierProvider.autoDispose<PushSettingsNotifier, PushSettings>(
  PushSettingsNotifier.new,
  retry: (_, _) => null,
);
