import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tencent_cloud_chat_sdk/enum/V2TimAdvancedMsgListener.dart';
import 'package:tencent_cloud_chat_sdk/enum/V2TimSDKListener.dart';
import 'package:tencent_cloud_chat_sdk/enum/log_level_enum.dart';
import 'package:tencent_cloud_chat_sdk/enum/message_elem_type.dart';
import 'package:tencent_cloud_chat_sdk/models/v2_tim_conversation.dart';
import 'package:tencent_cloud_chat_sdk/models/v2_tim_message.dart';
import 'package:tencent_cloud_chat_sdk/tencent_im_sdk_plugin.dart';

import '../network/api_paths.dart';
import '../network/dio_client.dart';

/// 一条 IM 消息（Story 5.5）。`who` ∈ me / peer / system；文字与图片二选一。
@immutable
class ImMessage {
  const ImMessage({required this.who, this.text, this.imageUrl, this.id});

  final String who; // me | peer | system
  final String? text;
  final String? imageUrl;

  /// 腾讯 IM msgID（bug 20260721-347）：渲染层按此去重，防同一条消息被上屏两次。
  final String? id;

  bool get isMine => who == 'me';
  bool get isSystem => who == 'system';
}

/// 一条会话的未读摘要（兽医工作台「进行中」卡角标用）。来自腾讯 IM SDK 会话对象。
@immutable
class ImConversationSummary {
  const ImConversationSummary({required this.unread, this.lastMessage});

  final int unread;
  final String? lastMessage; // 最近一条消息预览（图片消息折成占位符）

  static const empty = ImConversationSummary(unread: 0);
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
/// [LiveImService]：真机经腾讯 IM Flutter SDK（`tencent_cloud_chat_sdk`）直连 C2C 收发。
/// **前端绝不自签 UserSig / 不持 SecretKey**——登录凭证一律向后端短时换取。
abstract interface class ImService {
  /// 取 UserSig 并登录 IM（幂等：已登录则空转）。失败抛异常（调用方提示重试，不崩）。
  Future<void> loginIfNeeded();

  /// 登出 IM（离开会话 / 兽医下线 / 登出时）。
  Future<void> logout();

  /// 向对端发文字（C2C，peer=`u_<id>`/`v_<id>`）。
  Future<void> sendText({required String peerId, required String text});

  /// 向对端发图片（本地路径，C2C）。媒体留 IM，不落 OSS / 后端。
  Future<void> sendImage({required String peerId, required String filePath});

  /// 订阅与某对端（[peerId]）的实时消息流（取消订阅即离开）。
  Stream<ImMessage> onMessages(String peerId);

  /// 任意入站消息的轻量信号流（不带内容）——列表角标实时刷新触发用。
  Stream<void> get inboundSignals;

  /// 批量取若干对端会话的未读数 + 最近消息预览（工作台列表角标）。
  /// 未登录 / 失败 / 无会话 → 缺省返回空 Map，调用方按缺省优雅降级（不显角标）。
  Future<Map<String, ImConversationSummary>> conversationSummaries(List<String> peerIds);

  /// 标记与某对端的会话为已读（进入会话即清未读角标）。失败静默。
  Future<void> markRead(String peerId);

  /// 拉某对端最近历史消息（进入会话补全上文）。失败返回空表，不崩。
  Future<List<ImMessage>> loadHistory(String peerId, {int count});
}

/// 真机 live 实现（Story 5.5）。取 UserSig 后经腾讯 IM Flutter SDK 登录/收发。
///
/// 生命周期：首次 [loginIfNeeded] 惰性 `initSDK` + 注册 advanced 消息监听（仅一次），再以后端 UserSig
/// SDK `login`；[logout] 仅退登录、保留 SDK 实例与监听以便复登。收发走 C2C（按对端 userID，无需服务端会话）。
/// 护栏：仅用后端下发的短时 UserSig，绝不自签 / 绝不接触 SecretKey。
class LiveImService implements ImService {
  LiveImService({required this.dio});

  /// 腾讯 IM「未登录 / 被踢下线」错误码：发送时命中即重登一次重试。
  static const int _kImNotLoggedIn = 6014;

  final Dio dio;
  ImCredential? _credential;
  bool _sdkInited = false;
  V2TimAdvancedMsgListener? _listener;
  // 单飞登录（bug 20260721-347）：并发 loginIfNeeded 共享同一次登录，避免 initSDK/监听器被重复注册
  // 导致入站消息双份派发（兽医每条消息用户端收到两条）。
  Future<void>? _loginInFlight;

  /// 全量入站 C2C 消息广播（[onMessages] 按对端过滤）。服务随 Provider 贯穿 app 生命周期，不主动关闭。
  final StreamController<V2TimMessage> _incoming = StreamController<V2TimMessage>.broadcast();

  Future<ImCredential> _fetchUserSig() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.imUserSig);
    return ImCredential.fromJson(resp.data!);
  }

  Future<void> _ensureSdkInit(int sdkAppId) async {
    if (_sdkInited) return;
    // 幂等保险：SDK 已初始化过监听器则先解绑再注册，杜绝任何路径下双挂载（bug 20260721-347）。
    if (_listener != null) {
      await TencentImSDKPlugin.v2TIMManager
          .getMessageManager()
          .removeAdvancedMsgListener(listener: _listener!);
      _listener = null;
    }
    final init = await TencentImSDKPlugin.v2TIMManager.initSDK(
      sdkAppID: sdkAppId,
      loglevel: LogLevelEnum.V2TIM_LOG_ERROR,
      // 被踢下线 / UserSig 过期：清凭证（不在此处自动重登，避免双设备同账号互踢死循环）；
      // 下次 loginIfNeeded（进会话）或发送遇 6014 时再重登恢复。
      listener: V2TimSDKListener(
        onKickedOffline: () => _credential = null,
        onUserSigExpired: () => _credential = null,
      ),
    );
    if (init.code != 0) {
      throw StateError('IM initSDK 失败: ${init.code}');
    }
    _listener = V2TimAdvancedMsgListener()
      ..onRecvNewMessage = (V2TimMessage msg) {
        if (!_incoming.isClosed) _incoming.add(msg);
      };
    await TencentImSDKPlugin.v2TIMManager
        .getMessageManager()
        .addAdvancedMsgListener(listener: _listener!);
    _sdkInited = true;
  }

  @override
  Future<void> loginIfNeeded() {
    if (_credential != null) return Future.value();
    // 单飞（bug 20260721-347）：并发调用者共享同一次登录 Future，initSDK + addAdvancedMsgListener 只执行一次；
    // 完成后清空以便下次（登出/被踢后）可重登。
    return _loginInFlight ??= _doLogin().whenComplete(() => _loginInFlight = null);
  }

  Future<void> _doLogin() async {
    if (_credential != null) return;
    // 经后端 MAU 闸门取短时 UserSig（用户须有进行中会话，否则后端 403）。
    final cred = await _fetchUserSig();
    final sdkAppId = int.tryParse(cred.sdkAppId);
    if (sdkAppId == null || sdkAppId == 0) {
      throw StateError('IM SDKAppID 无效');
    }
    await _ensureSdkInit(sdkAppId);
    final res = await TencentImSDKPlugin.v2TIMManager
        .login(userID: cred.imUserId, userSig: cred.userSig);
    if (res.code != 0) {
      throw StateError('IM login 失败: ${res.code}');
    }
    _credential = cred;
  }

  @override
  Future<void> logout() async {
    _credential = null;
    if (!_sdkInited) return;
    try {
      await TencentImSDKPlugin.v2TIMManager.logout();
    } catch (_) {
      // 退登录失败不抛（页面 dispose 路径，吞掉避免噪声）。
    }
  }

  @override
  Future<void> sendText({required String peerId, required String text}) async {
    final mm = TencentImSDKPlugin.v2TIMManager.getMessageManager();
    final created = await mm.createTextMessage(text: text);
    final msg = created.data?.messageInfo;
    if (msg == null) {
      throw StateError('createTextMessage 失败: ${created.code}');
    }
    await _sendWithRelogin(msg, peerId, 'sendText');
  }

  @override
  Future<void> sendImage({required String peerId, required String filePath}) async {
    // 媒体留 IM，不落 OSS/后端：直接以本地路径建图片消息发出。
    final mm = TencentImSDKPlugin.v2TIMManager.getMessageManager();
    final created = await mm.createImageMessage(imagePath: filePath);
    final msg = created.data?.messageInfo;
    if (msg == null) {
      throw StateError('createImageMessage 失败: ${created.code}');
    }
    await _sendWithRelogin(msg, peerId, 'sendImage');
  }

  /// 发送 + 被踢自愈：遇 6014（未登录/被踢下线）→ 清凭证重登一次再重试，
  /// 解决「被踢后发送恒失败、需重启 App」（Story 5.5 live）。
  Future<void> _sendWithRelogin(V2TimMessage msg, String peerId, String label) async {
    final mm = TencentImSDKPlugin.v2TIMManager.getMessageManager();
    var res = await mm.sendMessage(message: msg, receiver: peerId, groupID: '');
    if (res.code == _kImNotLoggedIn) {
      _credential = null; // 强制下次 login 真正执行（绕过幂等 guard）
      await loginIfNeeded();
      res = await mm.sendMessage(message: msg, receiver: peerId, groupID: '');
    }
    if (res.code != 0) {
      throw StateError('$label 失败: ${res.code}');
    }
  }

  @override
  Stream<ImMessage> onMessages(String peerId) {
    // C2C：无论收发，V2TimMessage.userID 恒为对端 userID。按对端过滤本会话消息。
    return _incoming.stream.where((m) => m.userID == peerId).map(_toImMessage);
  }

  @override
  Stream<void> get inboundSignals => _incoming.stream.map((_) {});

  @override
  Future<Map<String, ImConversationSummary>> conversationSummaries(List<String> peerIds) async {
    if (peerIds.isEmpty) return const {};
    try {
      // C2C 会话 ID 形如 c2c_<peerUserID>；按对端账号批量回查未读 + 最近消息。
      final res = await TencentImSDKPlugin.v2TIMManager
          .getConversationManager()
          .getConversationListByConversationIds(
            conversationIDList: peerIds.map((p) => 'c2c_$p').toList(),
          );
      final list = res.data ?? const <V2TimConversation?>[];
      final out = <String, ImConversationSummary>{};
      for (final c in list) {
        final peer = c?.userID;
        if (peer == null) continue;
        out[peer] = ImConversationSummary(
          unread: c?.unreadCount ?? 0,
          lastMessage: _previewOf(c?.lastMessage),
        );
      }
      return out;
    } catch (_) {
      return const {};
    }
  }

  @override
  Future<void> markRead(String peerId) async {
    try {
      // cleanTimestamp/cleanSequence 传 0 → 清空该会话全部未读（markC2CMessageAsRead 已弃用）。
      await TencentImSDKPlugin.v2TIMManager.getConversationManager().cleanConversationUnreadMessageCount(
        conversationID: 'c2c_$peerId',
        cleanTimestamp: 0,
        cleanSequence: 0,
      );
    } catch (_) {
      // 标已读失败不影响会话本身（角标下次拉取仍会纠偏）。
    }
  }

  /// 最近一条消息预览：文字直取，图片折占位符，其它类型留空。
  String? _previewOf(V2TimMessage? m) {
    if (m == null) return null;
    if (m.elemType == MessageElemType.V2TIM_ELEM_TYPE_IMAGE) return '[📷]';
    return m.textElem?.text;
  }

  @override
  Future<List<ImMessage>> loadHistory(String peerId, {int count = 20}) async {
    try {
      final res = await TencentImSDKPlugin.v2TIMManager
          .getMessageManager()
          .getC2CHistoryMessageList(userID: peerId, count: count);
      final list = res.data ?? const <V2TimMessage>[];
      // SDK 返回新→旧，展示需旧→新。
      return list.reversed.map(_toImMessage).toList();
    } catch (_) {
      return const <ImMessage>[];
    }
  }

  ImMessage _toImMessage(V2TimMessage m) {
    final who = (m.isSelf ?? false) ? 'me' : 'peer';
    if (m.elemType == MessageElemType.V2TIM_ELEM_TYPE_IMAGE) {
      final images = m.imageElem?.imageList;
      final url = (images != null && images.isNotEmpty) ? images.first?.url : null;
      return ImMessage(who: who, imageUrl: url, id: m.msgID);
    }
    return ImMessage(who: who, text: m.textElem?.text, id: m.msgID);
  }
}

/// IM 能力 provider：真机连真后端时走 [LiveImService]。
final imServiceProvider = Provider<ImService>((ref) {
  return LiveImService(dio: ref.read(dioProvider));
});
