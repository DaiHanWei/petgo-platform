import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/im/im_service.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/case_image_viewer.dart';

/// 实时对话区（Story 5.5 · TailTopia Prototype VetChat 换肤）。
///
/// 经腾讯 IM Flutter SDK（`imServiceProvider` → [LiveImService]）做真实 C2C 收发：
/// 进入按 [peerId]（对端 `u_<userId>` / `v_<vetId>`）订阅实时消息流 + 拉最近历史，
/// 发送走 [ImService.sendText] / [ImService.sendImage]（媒体留 IM，不落 OSS）。
/// 视觉对齐原型气泡。[peerId] 为空（兽医未接单 / 未登录）时只渲染壳,不触 SDK。
///
/// 🔄 PRD V1.0.0（F4 · 2026-06-08）：V1.0.0 全程无视频，会话仅文字 / 图片。
class ImChatPlaceholder extends ConsumerStatefulWidget {
  const ImChatPlaceholder(
      {super.key,
      this.imConversationId,
      this.peerId,
      this.accent = AppColors.mint,
      this.selfIsVet = false,
      this.inputController});

  /// 外部输入框控制器（兽医侧「Pakai」预填用）。为空则内部自管理（并负责销毁）。
  final TextEditingController? inputController;

  /// 仅作展示 / Widget key 用途；真实 C2C 收发以 [peerId] 为准。
  final String? imConversationId;

  /// 对端 IM 账号（用户侧 `v_<vetId>` / 兽医侧 `u_<userId>`）。真机 C2C 收发用。
  final String? peerId;

  /// 己方气泡 + 发送钮品牌主色：用户侧紫 `AppColors.mint`(#845EC9 默认) / 兽医侧薄荷 `vetPrimary`(#5BCBBB)。
  /// 直接取品牌 token（非 M3 colorScheme.primary，避免色调偏移失真）。
  final Color accent;

  /// 视角：兽医侧 true（己方=兽医薄荷「D」头像，对端=用户紫「A」）/ 用户侧 false（己方=用户紫「A」，对端=兽医薄荷「D」）。
  /// 控气泡两侧头像配色/字母（原型 chat.html / vet-chat.html）。
  final bool selfIsVet;

  @override
  ConsumerState<ImChatPlaceholder> createState() => _ImChatPlaceholderState();
}

class _ImChatPlaceholderState extends ConsumerState<ImChatPlaceholder> {
  late final TextEditingController _input;
  late final bool _ownsInput;
  final _scroll = ScrollController();
  final _picker = ImagePicker();

  ImService? _service;
  StreamSubscription<ImMessage>? _sub;
  bool _bootstrapped = false;
  final List<ImMessage> _msgs = [];

  @override
  void initState() {
    super.initState();
    _ownsInput = widget.inputController == null;
    _input = widget.inputController ?? TextEditingController();
    _service = ref.read(imServiceProvider);
    _maybeBootstrap();
  }

  @override
  void didUpdateWidget(covariant ImChatPlaceholder oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 兽医接单后 peerId 由 null 变为 v_/u_ → 此时才挂载实时流。
    if (oldWidget.peerId != widget.peerId) _maybeBootstrap();
  }

  /// 订阅实时流 + 登录后拉历史（仅一次，幂等）。[peerId] 为空不触 SDK。
  void _maybeBootstrap() {
    final peer = widget.peerId;
    if (peer == null || peer.isEmpty || _bootstrapped) return;
    _bootstrapped = true;
    // 入站流广播：登录前订阅亦无害，登录成功后开始有消息流入。
    _sub = _service!.onMessages(peer).listen((m) {
      if (!mounted) return;
      setState(() => _msgs.add(m));
      _scrollToEnd();
      // 会话打开期间收到对端消息即标已读 → 工作台列表角标不残留。
      _service!.markRead(peer);
    });
    // 页面（consult/vet conversation）已驱动 loginIfNeeded；此处兜底再唤一次（幂等）后拉历史 + 进入即清未读。
    _service!.loginIfNeeded().then((_) {
      _service!.markRead(peer);
      return _loadHistory();
    }).catchError((_) {
      // 取 sig 403 / 网络失败：保留空壳，下次进入重试。
    });
  }

  Future<void> _loadHistory() async {
    final peer = widget.peerId;
    if (peer == null) return;
    final hist = await _service!.loadHistory(peer);
    if (!mounted || hist.isEmpty) return;
    setState(() => _msgs.insertAll(0, hist));
    _scrollToEnd();
  }

  @override
  void dispose() {
    _sub?.cancel();
    if (_ownsInput) _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
      }
    });
  }

  void _send() {
    final t = _input.text.trim();
    final peer = widget.peerId;
    if (t.isEmpty || peer == null) return;
    // 乐观上屏己方气泡（发送回执经 SDK；入站流只回对端消息）。
    setState(() {
      _msgs.add(ImMessage(who: 'me', text: t));
      _input.clear();
    });
    _scrollToEnd();
    _service!.sendText(peerId: peer, text: t).catchError((_) {
      if (mounted) _toast(AppLocalizations.of(context).imSendFailed);
    });
  }

  Future<void> _pickAndSendImage() async {
    final peer = widget.peerId;
    if (peer == null) return;
    final XFile? picked = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 85);
    if (picked == null || !mounted) return;
    setState(() {
      _msgs.add(ImMessage(who: 'me', imageUrl: picked.path)); // 本地路径乐观上屏
    });
    _scrollToEnd();
    _service!.sendImage(peerId: peer, filePath: picked.path).catchError((_) {
      if (mounted) _toast(AppLocalizations.of(context).imSendFailed);
    });
  }

  void _toast(String msg) {
    showAppToast(context, msg);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Expanded(
      key: const ValueKey('imChatPlaceholder'),
      child: Column(
        children: [
          Expanded(
            // Bug 20260701-173：点消息区空白/下拉收起键盘（iOS 无系统返回键，否则键盘收不起）。
            // translucent 不吞子节点点击(图片气泡)；共享组件内修复一处覆盖兽医端+用户端。
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onTap: () => FocusScope.of(context).unfocus(),
              child: _msgs.isEmpty
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Text(l10n.imChatPlaceholderHint,
                            textAlign: TextAlign.center,
                            style: const TextStyle(fontSize: 12.5, color: AppColors.muted, height: 1.5)),
                      ),
                    )
                  : ListView(
                      controller: _scroll,
                      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                      padding: const EdgeInsets.fromLTRB(14, 14, 14, 8),
                      children: [
                        for (final m in _msgs) _Bubble(msg: m, accent: widget.accent, selfIsVet: widget.selfIsVet),
                      ],
                    ),
            ),
          ),
          _InputBar(
            controller: _input,
            onSend: _send,
            onPickImage: _pickAndSendImage,
            accent: widget.accent,
          ),
        ],
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.msg, required this.accent, this.selfIsVet = false});

  final ImMessage msg;
  final Color accent;
  final bool selfIsVet;

  /// 28px 圆头像：薄荷实心（兽医「D」）/ 紫渐变（用户「A」）。
  Widget _avatar({required bool mint, required String label}) {
    return Container(
      width: 28,
      height: 28,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: mint ? AppColors.vetPrimary : null,
        gradient: mint
            ? null
            : const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [AppColors.mint500, AppColors.mint]),
      ),
      child: Text(label,
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (msg.isSystem) {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 6),
        alignment: Alignment.center,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 280),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(999)),
          child: Text(msg.text ?? '',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        ),
      );
    }

    final me = msg.isMine;

    // 头像：己方 me（兽医侧薄荷「D」/ 用户侧紫「A」）；对端（反之）。
    final meMint = selfIsVet; // 兽医视角己方=薄荷
    final avatar = me
        ? _avatar(mint: meMint, label: meMint ? 'D' : 'A')
        : _avatar(mint: !meMint, label: !meMint ? 'D' : 'A');

    Widget content;
    if (msg.imageUrl != null) {
      // 点击气泡图 → 全屏看大图（双指缩放，远端 url / 本地刚发图都支持）。
      content = GestureDetector(
        key: const ValueKey('imBubbleImage'),
        onTap: () => showCaseImageFullScreen(context, msg.imageUrl!),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(18),
          child: _image(msg.imageUrl!),
        ),
      );
    } else {
      content = Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: me ? accent : AppColors.card,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(18),
            topRight: const Radius.circular(18),
            bottomLeft: Radius.circular(me ? 18 : 6),
            bottomRight: Radius.circular(me ? 6 : 18),
          ),
          boxShadow: const [BoxShadow(color: Color(0x0A2B2A27), offset: Offset(0, 1), blurRadius: 2)],
        ),
        child: Text(msg.text ?? '',
            style: TextStyle(fontSize: 14.5, height: 1.45, color: me ? Colors.white : AppColors.ink)),
      );
    }

    final bubble = ConstrainedBox(
      constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.72),
      child: content,
    );

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4.5),
      child: Row(
        mainAxisAlignment: me ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: me
            ? [Flexible(child: bubble), const SizedBox(width: 8), avatar]
            : [avatar, const SizedBox(width: 8), Flexible(child: bubble)],
      ),
    );
  }

  /// 本地路径（乐观上屏）走 [Image.file]；远端 IM url 走 [Image.network]。
  Widget _image(String src) {
    final w = src.startsWith('http')
        ? Image.network(src, width: 150, height: 120, fit: BoxFit.cover)
        : Image.file(File(src), width: 150, height: 120, fit: BoxFit.cover);
    return SizedBox(width: 150, height: 120, child: w);
  }
}

class _InputBar extends StatelessWidget {
  const _InputBar(
      {required this.controller, required this.onSend, required this.onPickImage, required this.accent});

  final TextEditingController controller;
  final VoidCallback onSend;
  final VoidCallback onPickImage;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      decoration: const BoxDecoration(
        color: AppColors.card,
        border: Border(top: BorderSide(color: AppColors.line2)),
      ),
      child: Row(
        children: [
          GestureDetector(
            key: const ValueKey('vetChatPickImage'),
            onTap: onPickImage,
            child: Container(
              width: 40,
              height: 40,
              decoration: const BoxDecoration(color: AppColors.cream2, shape: BoxShape.circle),
              child: const Icon(Icons.photo_camera_outlined, size: 21, color: AppColors.ink2),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: controller,
              onSubmitted: (_) => onSend(),
              style: const TextStyle(fontSize: 15, color: AppColors.ink),
              decoration: InputDecoration(
                hintText: l10n.imInputHint,
                hintStyle: const TextStyle(color: AppColors.muted, fontSize: 15),
                filled: true,
                fillColor: AppColors.cream2,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(999),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          GestureDetector(
            key: const ValueKey('vetChatSend'),
            onTap: onSend,
            child: Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(color: accent, shape: BoxShape.circle), // 用户侧紫 / 兽医侧薄荷
              child: const Icon(Icons.send_rounded, size: 21, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}
