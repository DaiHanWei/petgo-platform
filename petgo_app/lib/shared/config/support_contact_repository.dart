import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_paths.dart';
import '../../core/network/dio_client.dart';
import 'support_contact.dart';

/// 客服联系方式数据层（Story 3-1）。
///
/// 🔓 端点**免鉴权**：客服弹窗在登录前也会出现（兽医登录页就有一个）。
class SupportContactRepository {
  SupportContactRepository({required this.dio});

  final Dio dio;

  Future<SupportContact> fetch() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.supportContact);
    return SupportContact.fromJson(resp.data!);
  }
}

final Provider<SupportContactRepository> supportContactRepositoryProvider =
    Provider<SupportContactRepository>(
        (ref) => SupportContactRepository(dio: ref.read(dioProvider)));

/// 当前客服联系方式。
///
/// 🔴 **内部吞掉任何异常，返回 [kFallbackSupportContact]，让本 provider 永不进 error 态。**
/// 调用方若用 `.when(error:)` 就会画出空白或错误块 —— 而客服号是「其它路都走不通时」
/// 用户最后能抓住的东西，这里出现一个错误块等于把最后一条路也堵死。
/// 失败的正确表现是「显示一个可能有点旧、但一定能打通的号码」。
///
/// 🔴 **必须 `autoDispose`**：上面那个 catch 把失败变成了一个*成功*值。非 autoDispose 的
/// FutureProvider 会把它缓存一整个进程 —— 用户在地铁里打开过一次客服弹窗，
/// 此后整个 App 生命周期都钉死在兜底号码上，再也不会重新请求，
/// 「改后台配置即生效」当场落空。autoDispose 让每次没人监听时丢弃缓存，下次打开重新拉。
final supportContactProvider =
    FutureProvider.autoDispose<SupportContact>((ref) async {
  try {
    return await ref.read(supportContactRepositoryProvider).fetch();
  } catch (_) {
    return kFallbackSupportContact;
  }
});
