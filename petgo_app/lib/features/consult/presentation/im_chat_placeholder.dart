import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../shared/widgets/design/striped_photo.dart';

/// 实时对话区（Story 5.5 · PetGo Prototype VetChat 换肤）。
///
/// 真机接入腾讯 IM Flutter SDK 收发文字/图片/视频（≤60s）+ 后台保连（NFR-5）属 **L2**。
/// 本组件为 demo/占位聊天面：种子对话 + 本地回声（发送→打字→兽医罐头回复），
/// 视觉对齐原型气泡。真机接入时用 [imConversationId] 替换为 IM SDK 会话组件。
class ImChatPlaceholder extends StatefulWidget {
  const ImChatPlaceholder({super.key, this.imConversationId});

  final String? imConversationId;

  @override
  State<ImChatPlaceholder> createState() => _ImChatPlaceholderState();
}

class _ChatMsg {
  const _ChatMsg.sys(this.text)
      : who = 'sys',
        photo = null;
  const _ChatMsg.me(this.text)
      : who = 'me',
        photo = null;
  const _ChatMsg.vet(this.text)
      : who = 'vet',
        photo = null;
  const _ChatMsg.photo(this.photo)
      : who = 'me',
        text = null;

  final String who;
  final String? text;
  final String? photo;
}

class _ImChatPlaceholderState extends State<ImChatPlaceholder> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  bool _typing = false;

  final List<_ChatMsg> _msgs = [
    const _ChatMsg.sys('Konsultasi dengan drh. Sari dimulai. Sampaikan keluhan anabul-mu ya 🐾'),
    const _ChatMsg.me('Halo dok, kucing saya tadi muntah busa putih 2x malam ini 😟'),
    const _ChatMsg.photo('foto anabul'),
    const _ChatMsg.vet('Halo Kak, terima kasih fotonya. Apakah masih mau makan & minum? Lemas atau tidak?'),
    const _ChatMsg.me('Masih mau minum sedikit, agak diem aja'),
    const _ChatMsg.vet(
        'Baik. Untuk sekarang puasakan makanan 2-3 jam, tetap sediakan air. Pantau apakah muntah berulang. Kalau >3x atau makin lemas, sebaiknya ke klinik ya.'),
  ];

  @override
  void dispose() {
    _input.dispose();
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
    if (t.isEmpty) return;
    setState(() {
      _msgs.add(_ChatMsg.me(t));
      _input.clear();
      _typing = true;
    });
    _scrollToEnd();
    Future.delayed(const Duration(milliseconds: 1500), () {
      if (!mounted) return;
      setState(() {
        _typing = false;
        _msgs.add(const _ChatMsg.vet(
            'Siap, saya catat ya. Tetap pantau kondisinya dalam 2 jam ke depan 🙏'));
      });
      _scrollToEnd();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Expanded(
      key: const ValueKey('imChatPlaceholder'),
      child: Column(
        children: [
          Expanded(
            child: ListView(
              controller: _scroll,
              padding: const EdgeInsets.fromLTRB(14, 14, 14, 8),
              children: [
                for (final m in _msgs) _Bubble(msg: m),
                if (_typing) const _Bubble(msg: _ChatMsg.vet('...'), typing: true),
              ],
            ),
          ),
          _InputBar(controller: _input, onSend: _send),
        ],
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.msg, this.typing = false});

  final _ChatMsg msg;
  final bool typing;

  @override
  Widget build(BuildContext context) {
    if (msg.who == 'sys') {
      return Container(
        margin: const EdgeInsets.symmetric(vertical: 6),
        alignment: Alignment.center,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 280),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(999)),
          child: Text(msg.text!,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        ),
      );
    }

    final me = msg.who == 'me';
    final align = me ? Alignment.centerRight : Alignment.centerLeft;

    Widget content;
    if (msg.photo != null) {
      content = ClipRRect(
        borderRadius: BorderRadius.circular(18),
        child: StripedPhoto(label: msg.photo!, height: 120, width: 150, radius: 18),
      );
    } else {
      content = Container(
        padding: typing
            ? const EdgeInsets.symmetric(horizontal: 16, vertical: 12)
            : const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: me ? AppColors.mint : AppColors.card,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(18),
            topRight: const Radius.circular(18),
            bottomLeft: Radius.circular(me ? 18 : 6),
            bottomRight: Radius.circular(me ? 6 : 18),
          ),
          boxShadow: const [BoxShadow(color: Color(0x0A2B2A27), offset: Offset(0, 1), blurRadius: 2)],
        ),
        child: typing
            ? const _TypingDots()
            : Text(msg.text!,
                style: TextStyle(
                    fontSize: 14.5, height: 1.45, color: me ? Colors.white : AppColors.ink)),
      );
    }

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4.5),
      alignment: align,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.8),
        child: content,
      ),
    );
  }
}

class _TypingDots extends StatefulWidget {
  const _TypingDots();

  @override
  State<_TypingDots> createState() => _TypingDotsState();
}

class _TypingDotsState extends State<_TypingDots> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 900))..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 32,
      height: 8,
      child: AnimatedBuilder(
        animation: _c,
        builder: (_, _) => Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (int i = 0; i < 3; i++) ...[
              if (i > 0) const SizedBox(width: 4),
              Opacity(
                opacity: (((_c.value + i * 0.2) % 1.0) < 0.5) ? 1.0 : 0.3,
                child: Container(
                  width: 7,
                  height: 7,
                  decoration: const BoxDecoration(color: AppColors.muted, shape: BoxShape.circle),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _InputBar extends StatelessWidget {
  const _InputBar({required this.controller, required this.onSend});

  final TextEditingController controller;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      decoration: const BoxDecoration(
        color: AppColors.card,
        border: Border(top: BorderSide(color: AppColors.line2)),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: const BoxDecoration(color: AppColors.cream2, shape: BoxShape.circle),
            child: const Icon(Icons.photo_camera_outlined, size: 21, color: AppColors.ink2),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: controller,
              onSubmitted: (_) => onSend(),
              style: const TextStyle(fontSize: 15, color: AppColors.ink),
              decoration: InputDecoration(
                hintText: 'Tulis pesan...',
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
              decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
              child: const Icon(Icons.send_rounded, size: 21, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}
