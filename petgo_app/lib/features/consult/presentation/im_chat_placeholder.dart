import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/design/striped_photo.dart';

/// 实时对话区（Story 5.5 · TailTopia Prototype VetChat 换肤）。
///
/// 真机接入腾讯 IM Flutter SDK 收发文字/图片 + 后台保连（NFR-5）属 **L2**。
/// 🔄 PRD V1.0.0 修订（F4 · 2026-06-08）：V1.0.0 全程无视频，会话仅文字/图片（视频随收费模式后置）。
/// 本组件为 demo/占位聊天面：种子对话 + 本地回声（发送→打字→兽医罐头回复），
/// 视觉对齐原型气泡。
///
/// 🔌 真机接入（**L2 待本地**）：登录/收发由页面经 `imServiceProvider` 驱动（见
/// `consult_conversation_page` / `vet_conversation_page`）；[peerId]（对端 `u_`/`v_` 账号）与
/// [imConversationId] 用于真实 C2C 收发，绑定腾讯 IM Flutter SDK 后此面用 [ImService.onMessages]/`sendText`
/// 替换本地回声，视觉保留。mock / 测试下保持本地演示气泡（不触真实 SDK）。
class ImChatPlaceholder extends StatefulWidget {
  const ImChatPlaceholder({super.key, this.imConversationId, this.peerId});

  final String? imConversationId;

  /// 对端 IM 账号（用户侧 `v_<vetId>` / 兽医侧 `u_<userId>`）。真机 C2C 收发用（L2）。
  final String? peerId;

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
    const _ChatMsg.me('Halo dok, kucing saya Oyen tadi muntah busa putih 2x malam ini 😟'),
    const _ChatMsg.me('Dari sore dia jadi lebih diam, nggak seaktif biasanya'),
    const _ChatMsg.photo('Oyen tadi malam'),
    const _ChatMsg.vet(
        'Halo Kak, saya drh. Sari ya. Terima kasih fotonya 🙏 Oyen umur berapa, dan terakhir vaksin/obat cacing kapan?'),
    const _ChatMsg.me('Umur 2 tahun, vaksin rutin. Obat cacing terakhir sekitar 3 bulan lalu'),
    const _ChatMsg.vet(
        'Baik. Sekarang masih mau makan & minum nggak? Pup dan pipisnya gimana? Ada kemungkinan dia gigit benang/tanaman/makanan asing?'),
    const _ChatMsg.me(
        'Minum masih mau sedikit, makan belum mau sama sekali. Pup normal kemarin. Tadi sempat gigit-gigit tali tirai sih 😅'),
    const _ChatMsg.vet(
        'Noted. Muntah busa putih + nafsu makan turun paling sering karena iritasi lambung ringan atau hairball. Tapi karena ada riwayat gigit tali, kita tetap waspadai kemungkinan benda asing ya.'),
    const _ChatMsg.vet(
        'Untuk sekarang:\n1) Puasakan makanan 2-3 jam dulu\n2) Tetap sediakan air, kasih sedikit tapi sering\n3) Setelah itu coba makanan basah hambar (ayam rebus tanpa bumbu) porsi kecil'),
    const _ChatMsg.me('Oke dok. Kalau nanti masih muntah lagi gimana?'),
    const _ChatMsg.vet(
        'Kalau muntah berulang >3x, ada darah, lemas berat, atau sama sekali nggak mau minum dalam 12 jam → segera ke klinik untuk cek fisik & kemungkinan rontgen, mastiin nggak ada tali yang tertelan ya.'),
    const _ChatMsg.me('Baik dok, makasih banyak penjelasannya 🙏'),
    const _ChatMsg.vet(
        'Sama-sama Kak 🙏 Saya rangkum konsultasinya ya:\n• Dugaan: iritasi lambung ringan / hairball\n• Tindakan: puasa 2-3 jam → hidrasi → makanan hambar porsi kecil\n• Pantau 24 jam, waspada benda asing (tali tirai)\n• Ke klinik bila muntah berulang / lemas / ada darah\nSemoga Oyen cepat pulih! 🐱'),
    const _ChatMsg.sys('drh. Sari melampirkan kesimpulan konsultasi. Kamu bisa mengakhiri sesi & memberi rating.'),
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
    final l10n = AppLocalizations.of(context);
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
              decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
              child: const Icon(Icons.send_rounded, size: 21, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}
