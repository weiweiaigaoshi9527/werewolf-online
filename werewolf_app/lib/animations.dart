import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'theme.dart';

/// 开局身份牌翻牌揭示：牌背 → 3D 翻转 → 亮出阵营与角色。
/// 由调用方保证**每局只播一次**（用 gameId 去重），播完或点击即关闭。
class RoleReveal extends StatefulWidget {
  const RoleReveal({
    super.key,
    required this.role,
    required this.faction,
    required this.seat,
    this.onDone,
    this.duration = const Duration(milliseconds: 1100),
  });

  final String role;
  final String faction; // GOD / WOLF / 其它按好人处理
  final int seat;
  final VoidCallback? onDone;
  final Duration duration;

  @override
  State<RoleReveal> createState() => _RoleRevealState();
}

class _RoleRevealState extends State<RoleReveal> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: widget.duration);
  bool _disposed = false;

  /// onDone 只允许触发一次：点击收起与动画自然结束可能撞在一起，
  /// 而回调里通常是 Navigator.pop —— 第二次就会把上一层页面也弹掉。
  bool _notified = false;

  @override
  void initState() {
    super.initState();
    _c.forward().whenComplete(() => _finish());
  }

  void _finish() {
    if (_notified || _disposed) return;
    _notified = true;
    widget.onDone?.call();
  }

  @override
  void dispose() {
    _disposed = true;
    _c.dispose();
    super.dispose();
  }

  bool get _isWolf => widget.faction == 'WOLF';
  Color get _accent => _isWolf ? kBlood : (_isGod ? kMoon : kGreen);
  bool get _isGod => widget.faction == 'GOD';

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _finish,
      child: AnimatedBuilder(
        animation: _c,
        builder: (context, _) {
          final t = _c.value;
          // 前半段翻牌背，后半段亮牌面；用 cos 模拟透视收缩
          final angle = t < 0.5 ? math.pi * (1 - t * 2) * 0.0 + (t * 2) * math.pi / 2 : math.pi / 2 + ((t - 0.5) * 2) * math.pi / 2;
          final shrink = (math.cos(angle).abs()).clamp(0.06, 1.0);
          final showFace = t >= 0.5;
          return Container(
            color: const Color(0xE607040C),
            child: Center(
              child: Transform(
                alignment: Alignment.center,
                transform: Matrix4.identity()
                  ..setEntry(3, 2, 0.0016)
                  ..scale(shrink, showFace ? 1.0 + (t - 0.5) * 0.16 : 1.0, 1.0),
                child: Opacity(
                  opacity: showFace ? ((t - 0.5) * 2).clamp(0.0, 1.0) : 1.0,
                  child: Container(
                    width: 210,
                    height: 290,
                    decoration: BoxDecoration(
                      gradient: showFace
                          ? LinearGradient(colors: [kPanel, _accent.withOpacity(.28)], begin: Alignment.topLeft, end: Alignment.bottomRight)
                          : const LinearGradient(colors: [Color(0xFF1B1430), Color(0xFF0B0813)], begin: Alignment.topLeft, end: Alignment.bottomRight),
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(color: showFace ? _accent : kBorder, width: 2),
                      boxShadow: [BoxShadow(color: _accent.withOpacity(showFace ? .45 : 0), blurRadius: 34, spreadRadius: 3)],
                    ),
                    child: showFace
                        ? Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Text(_isWolf ? '🐺' : (_isGod ? '🌙' : '🧑'), style: const TextStyle(fontSize: 62)),
                              const SizedBox(height: 10),
                              Text(_isWolf ? '狼人阵营' : (_isGod ? '神民阵营' : '好人阵营'),
                                  style: TextStyle(color: _accent, fontSize: 15, letterSpacing: 2)),
                              const SizedBox(height: 6),
                              Text(widget.role,
                                  style: const TextStyle(color: kMoon, fontSize: 30, fontWeight: FontWeight.bold)),
                              const SizedBox(height: 14),
                              Text('你是 ${widget.seat} 号 · 点击继续',
                                  style: const TextStyle(color: kDim, fontSize: 12.5)),
                            ],
                          )
                        : const Center(
                            child: Text('🌙', style: TextStyle(fontSize: 58, color: Color(0xFF3A2F55))),
                          ),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// 结算揭示：胜负横幅先落，随后每个座位的身份按序淡入上移。
class SettleReveal extends StatelessWidget {
  const SettleReveal({
    super.key,
    required this.winner,
    required this.seats,
    this.duration = const Duration(milliseconds: 900),
  });

  final String winner;

  /// 每项需要 seat / role / alive 三个键。
  final List<Map> seats;
  final Duration duration;

  bool get _wolfWin => winner.contains('狼');

  @override
  Widget build(BuildContext context) {
    final color = _wolfWin ? kBlood : kGreen;
    return Column(
      children: [
        // 用 TweenAnimationBuilder 而不是持有 Controller：结算面板是 build 出来的，
        // 无状态动画不会因为上层重建而重播或泄漏。
        TweenAnimationBuilder<double>(
          tween: Tween(begin: 0, end: 1),
          duration: duration,
          curve: Curves.easeOutBack,
          builder: (context, v, child) => Transform.translate(
            offset: Offset(0, -14 * (1 - v)),
            child: Opacity(opacity: v.clamp(0.0, 1.0), child: child),
          ),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
            decoration: BoxDecoration(
              color: color.withOpacity(.16),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: color, width: 1.6),
            ),
            child: Text(_wolfWin ? '🐺 狼人阵营胜利' : '🌅 好人阵营胜利',
                style: TextStyle(color: color, fontSize: 19, fontWeight: FontWeight.bold, letterSpacing: 1.5)),
          ),
        ),
        const SizedBox(height: 10),
        Wrap(
          alignment: WrapAlignment.center,
          spacing: 8,
          runSpacing: 8,
          children: [
            for (var i = 0; i < seats.length; i++)
              TweenAnimationBuilder<double>(
                tween: Tween(begin: 0, end: 1),
                // 逐个错开，形成"依次亮牌"的节奏
                duration: duration + Duration(milliseconds: 110 * (i + 1)),
                builder: (context, v, child) => Opacity(
                  opacity: v.clamp(0.0, 1.0),
                  child: Transform.scale(scale: 0.9 + 0.1 * v, child: child),
                ),
                child: _chip(seats[i]),
              ),
          ],
        ),
      ],
    );
  }

  Widget _chip(Map s) {
    final seat = (s['seat'] as num?)?.toInt() ?? 0;
    final role = '${s['role'] ?? '?'}';
    final alive = s['alive'] == true;
    final wolf = role.contains('狼');
    final c = wolf ? kBlood : (alive ? kGreen : kDim);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: kPanel,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: c.withOpacity(.75)),
      ),
      child: Text('$seat·$role${alive ? '' : '†'}',
          style: TextStyle(fontSize: 12.5, color: c, decoration: alive ? null : TextDecoration.lineThrough)),
    );
  }
}

/// 说明：上面两处动画直接用框架的 `TweenAnimationBuilder`（不要再自己实现同名组件，
/// 那会遮蔽 material 导出的版本，既难查又容易在升级时行为漂移）。
