import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 主人四字母类型数据层（V1.3.2 Story 2.5）。账号级；配型结果不落库、纯客户端现算（AD-3）。
class TailsonalityOwnerTypeRepository {
  TailsonalityOwnerTypeRepository({required this.dio});

  final Dio dio;

  /// 未设置 → null（后端下发 `{}`，缺键即未设置）。
  Future<String?> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meTailsonalityOwnerType);
    return ownerTypeFromJson(resp.data);
  }

  /// 保存 / 覆盖；返回服务端确认的类型。非法值服务端回 422。
  Future<String> save(String typeCode) async {
    final resp = await dio.put<Map<String, dynamic>>(ApiPaths.meTailsonalityOwnerType, data: {'typeCode': typeCode});
    return ownerTypeFromJson(resp.data) ?? typeCode;
  }
}

/// `{typeCode}` 线格式解析：缺键 / 非字符串 / 非四字母 → null（fail-closed：当作未设置，让用户重选）。
String? ownerTypeFromJson(Map<String, dynamic>? json) {
  final v = json?['typeCode'];
  return v is String && RegExp(r'^[EI][NS][TF][JP]$').hasMatch(v) ? v : null;
}

final tailsonalityOwnerTypeRepositoryProvider = Provider<TailsonalityOwnerTypeRepository>(
    (ref) => TailsonalityOwnerTypeRepository(dio: ref.read(dioProvider)));

/// 当前账号的主人类型（账号级常驻）。🔴 已登记 `resetUserScopedCaches`：换账号不得沿用上一账号的类型。
class TailsonalityOwnerTypeNotifier extends AsyncNotifier<String?> {
  @override
  Future<String?> build() => ref.read(tailsonalityOwnerTypeRepositoryProvider).fetch();

  /// 保存成功才更新状态；失败向上抛，由页面保持选择器与当前选中并提示。
  Future<void> set(String typeCode) async {
    final saved = await ref.read(tailsonalityOwnerTypeRepositoryProvider).save(typeCode);
    state = AsyncData(saved);
  }
}

final tailsonalityOwnerTypeProvider =
    AsyncNotifierProvider<TailsonalityOwnerTypeNotifier, String?>(TailsonalityOwnerTypeNotifier.new);
