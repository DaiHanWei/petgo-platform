import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../network/api_paths.dart';
import '../network/dio_client.dart';

/// 一条 IM 消息（Story 5.5）。`who` ∈ me / peer / system；文字与图片二选一。
@immutable
class ImMessage {
  const ImMessage({required this.who, this.text, this.imageUrl});

  final String who; // me | peer | system
  final String? text;
  final String? imageUrl;

  bool get isMine => who == 'me';
  bool get isSystem => who == 'system';
}

/// 取自 `/im/usersig` 的登录凭证（客户端 SDK 登录 IM 用）。SecretKey 绝不下发，此处只有短时 UserSig。
@immutable
class ImCredential {
  const ImCredential({required this.imUserId, required this.userSig, required this.sdkAppId});

  final String imUserId;
  final String userSig;
  final String sdkAppId;

  factory ImCredential.fromJson(Map<String, dynamic> json) => ImCredential(
        imUserId: (json['imUserId'] ?? '') as String,
        userSig: (json['userSig'] ?? '') as String,
        sdkAppId: (json['sdkAppId'] ?? '').toString(),
      );
}

/// IM 会话能力封装（Story 5.5 live 增量）。
///
/// 抽象 login/logout/收发/监听，UserSig 取自后端 `/im/usersig`（用户态由后端 MAU 闸门控）。
/// [LiveImService]：真机经腾讯 IM Flutter SDK 直连收发（**L2 待本地接 `tencent_cloud_chat_sdk`**）；
/// 本批次（云端 headless）SDK 依赖未引入，[LiveImService] 先落「取 UserSig + 生命周期」骨架，
/// 实际 SDK login/收发标注待本地，绝不在前端自签 UserSig / 硬编码 SecretKey。
abstract interface class ImService {
  /// 取 UserSig 并登录 IM（幂等：已登录则空转）。失败抛 [DioException]（调用方提示重试，不崩）。
  Future<void> loginIfNeeded();

  /// 登出 IM（离开会话 / 兽医下线 / 登出时）。
  Future<void> logout();

  /// 向对端发文字（C2C，peer=`u_<id>`/`v_<id>`）。
  Future<void> sendText({required String peerId, required String text});

  /// 向对端发图片（本地路径，C2C）。媒体留 IM，不落 OSS / 后端。
  Future<void> sendImage({required String peerId, required String filePath});

  /// 订阅与某会话的实时消息流（离开时取消订阅）。
  Stream<ImMessage> onMessages(String conversationId);
}

/// 真机 live 实现（Story 5.5）。取 UserSig 后经腾讯 IM Flutter SDK 登录/收发。
///
/// **L2 待本地**：`tencent_cloud_chat_sdk` 依赖与原生权限本批次未引入（Ask First + 云端拉包不稳），
/// 故 SDK `login/sendMessage/onRecvNewMessage` 接入点以 TODO 标注。已实现：经后端取 UserSig（验 MAU 闸门链路），
/// 生命周期幂等。绝不在前端自签 UserSig / 硬编码 SecretKey。
class LiveImService implements ImService {
  LiveImService({required this.dio});

  final Dio dio;
  ImCredential? _credential;

  Future<ImCredential> _fetchUserSig() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.imUserSig);
    return ImCredential.fromJson(resp.data!);
  }

  @override
  Future<void> loginIfNeeded() async {
    if (_credential != null) return;
    // 经后端 MAU 闸门取短时 UserSig（用户须有进行中会话，否则后端 403）。
    final cred = await _fetchUserSig();
    _credential = cred;
    // TODO(L2 本地): TencentImSDKPlugin.v2TIMManager.login(userID: cred.imUserId, userSig: cred.userSig)
  }

  @override
  Future<void> logout() async {
    _credential = null;
    // TODO(L2 本地): TencentImSDKPlugin.v2TIMManager.logout()
  }

  @override
  Future<void> sendText({required String peerId, required String text}) async {
    // TODO(L2 本地): v2TIMManager.getMessageManager().createTextMessage + sendMessage(receiver: peerId)
  }

  @override
  Future<void> sendImage({required String peerId, required String filePath}) async {
    // TODO(L2 本地): createImageMessage(imagePath: filePath) + sendMessage（媒体留 IM，不落 OSS/后端）
  }

  @override
  Stream<ImMessage> onMessages(String conversationId) {
    // TODO(L2 本地): 桥接 v2TIMManager.addAdvancedMsgListener.onRecvNewMessage → ImMessage 流
    return const Stream.empty();
  }
}

/// IM 能力 provider：真机连真后端时走 [LiveImService]。
final imServiceProvider = Provider<ImService>((ref) {
  return LiveImService(dio: ref.read(dioProvider));
});
