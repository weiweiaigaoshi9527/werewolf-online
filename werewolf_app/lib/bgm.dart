/// 背景音乐（P4）：复刻网页端 `static/js/extras.js` 里的 BGM 模块。
///
/// 网页端把本机选的 MP3 以 Blob 存进 IndexedDB（`ww-bgm` / `tracks`，不上传服务端）。
/// Flutter 端沿用本项目既有约定（见 appearance.dart 的自定义背景）只存**本机绝对路径**，
/// 播放时直接读原文件。默认音量 0.6、循环默认勾选、列表/索引/收尾行为都与网页端一致。
///
/// 与 Sfx（对局音效）互不影响：网页端 🔊 与 🎵 也是两个独立开关。
library;

import 'dart:async';
import 'dart:convert';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 一首本地曲目：id 用于删除定位，name 用于展示，path 是本机 MP3 绝对路径。
class BgmTrack {
  const BgmTrack({required this.id, required this.name, required this.path});
  final String id;
  final String name;
  final String path;

  Map<String, Object?> toJson() => {'id': id, 'name': name, 'path': path};

  /// 容错解析：字段缺失/类型不符一律丢弃该条（旧版本残留、手改过的存档）。
  static BgmTrack? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    final name = raw['name'];
    final path = raw['path'];
    if (id is! String || name is! String || path is! String) return null;
    if (id.isEmpty || path.isEmpty) return null;
    return BgmTrack(id: id, name: name, path: path);
  }
}

/// 播放列表的下标推进规则（纯函数，便于离线单测）。
/// 返回 -1 表示"没有可播的曲目"。
int bgmNextIndex(int cur, int len) => len <= 0 ? -1 : (cur + 1) % len;
int bgmPrevIndex(int cur, int len) => len <= 0 ? -1 : (cur - 1 + len) % len;

/// 一曲播完后该去哪：循环时绕回 0，否则返回 -1 表示停下（与网页端 ended 回调一致）。
int bgmAdvanceAfterEnd(int cur, int len, bool loop) {
  if (len <= 0) return -1;
  final next = cur + 1;
  if (next < len) return next;
  return loop ? 0 : -1;
}

/// 背景音乐播放器（全局单例，见文件末尾 [bgm]）。
///
/// 音频相关调用一律"静默失败"：没有音频设备、平台通道未就绪、文件已被移走，
/// 都不该让界面崩掉——与网页端整段 try/catch 的取向一致。
class Bgm extends ChangeNotifier {
  static const _key = 'ww_bgm'; // 与网页端 IndexedDB 库名同名语义

  final List<BgmTrack> tracks = [];
  int _cur = -1;
  bool _playing = false;
  bool _loop = true; // 网页端 <input id="bgm-loop" checked>
  double _volume = 0.6; // 网页端 audio.volume = 0.6
  bool _audioDead = false; // 平台无音频能力时置位，之后一律不再碰播放器
  AudioPlayer? _player;

  int get cur => _cur;
  bool get playing => _playing;
  bool get loop => _loop;
  double get volume => _volume;
  bool get isEmpty => tracks.isEmpty;

  /// 从本机存档读回播放列表（与网页端一样，只存列表，不自动播放）。
  Future<void> load() async {
    try {
      final p = await SharedPreferences.getInstance();
      final raw = p.getString(_key);
      if (raw == null || raw.isEmpty) return;
      final list = jsonDecode(raw) as List;
      tracks
        ..clear()
        ..addAll(list.map(BgmTrack.fromJson).whereType<BgmTrack>());
      notifyListeners();
    } catch (_) {}
  }

  Future<void> _save() async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(_key, jsonEncode(tracks.map((t) => t.toJson()).toList()));
    } catch (_) {}
  }

  /// 把选中的本机文件加进列表并落盘；若此前什么都没在播，则自动从第 1 首开始
  /// （网页端 pickFiles 的 `if (cur < 0 && tracks.length) play(0)`）。返回新增条数。
  Future<int> addFiles(List<String> paths, List<String> names) async {
    if (paths.isEmpty) return 0;
    final stamp = DateTime.now().microsecondsSinceEpoch;
    for (var i = 0; i < paths.length; i++) {
      tracks.add(BgmTrack(id: '$stamp-$i', name: names[i], path: paths[i]));
    }
    await _save();
    notifyListeners();
    if (_cur < 0 && tracks.isNotEmpty) await play(0);
    return paths.length;
  }

  /// 移除一首；移除的正好是当前曲目时先停再清下标（网页端 del 分支）。
  Future<void> remove(String id) async {
    final idx = tracks.indexWhere((t) => t.id == id);
    if (idx < 0) return;
    if (idx == _cur) {
      await _stopAudio();
      _playing = false;
      _cur = -1;
    }
    tracks.removeAt(idx);
    if (_cur >= tracks.length) _cur = -1;
    await _save();
    notifyListeners();
  }

  /// 播第 idx 首（越界自动绕回；idx<0 取最后一首），语义同网页端 play(idx)。
  Future<void> play(int idx) async {
    if (tracks.isEmpty) return;
    if (idx < 0) idx = tracks.length - 1;
    if (idx >= tracks.length) idx = 0;
    _cur = idx;
    notifyListeners();
    await _guarded(() async {
      final p = await _ensure();
      if (p == null) return;
      await p.stop();
      await p.setSource(DeviceFileSource(tracks[idx].path));
      await p.resume();
    });
    _playing = _player != null;
    notifyListeners();
  }

  /// 播放/暂停。返回 false 表示"列表为空，调用方该提示先添加音乐"。
  Future<bool> toggle() async {
    if (tracks.isEmpty) return false;
    if (_cur < 0) {
      await play(0);
      return true;
    }
    final p = await _ensure();
    if (p == null) return true;
    if (_playing) {
      await _guarded(p.pause);
      _playing = false;
    } else {
      await _guarded(p.resume);
      _playing = true;
    }
    notifyListeners();
    return true;
  }

  Future<void> next() async {
    final i = bgmNextIndex(_cur, tracks.length);
    if (i >= 0) await play(i);
  }

  Future<void> prev() async {
    final i = bgmPrevIndex(_cur, tracks.length);
    if (i >= 0) await play(i);
  }

  Future<void> setVolume(double v) async {
    _volume = v.clamp(0, 1).toDouble();
    notifyListeners();
    final p = await _ensure();
    if (p != null) await _guarded(() => p.setVolume(_volume));
  }

  void setLoop(bool v) {
    if (v == _loop) return;
    _loop = v;
    notifyListeners();
  }

  /// 一曲播完：按 [bgmAdvanceAfterEnd] 决定续播还是停下。
  void _onComplete() {
    final i = bgmAdvanceAfterEnd(_cur, tracks.length, _loop);
    if (i < 0) {
      _playing = false;
      notifyListeners();
      return;
    }
    play(i);
  }

  /// 懒建播放器。构造时会订阅一个 EventChannel，那个错误在 stream 监听里抛出，
  /// 普通 try/catch 抓不到（见 sfx.dart 的同类处理），所以整段放进 runZonedGuarded。
  Future<AudioPlayer?> _ensure() async {
    if (_audioDead) return null;
    if (_player != null) return _player;
    AudioPlayer? made;
    await runZonedGuarded(() async {
      final p = AudioPlayer();
      await p.setVolume(_volume);
      p.onPlayerComplete.listen((_) => _onComplete());
      made = p;
    }, (e, s) {
      _audioDead = true;
      debugPrint('Bgm 播放器初始化失败: $e');
    });
    _player = made;
    if (made == null) _audioDead = true;
    return made;
  }

  Future<void> _stopAudio() async {
    final p = _player;
    if (p == null) return;
    await _guarded(p.stop);
  }

  /// 包一层 runZonedGuarded：音频异常只留痕，绝不冒泡成未处理异常。
  Future<void> _guarded(Future<void> Function() f) async {
    var failure = Object();
    StackTrace? trace;
    await runZonedGuarded(() async {
      try {
        await f();
      } catch (e, s) {
        failure = e;
        trace = s;
      }
    }, (e, s) {
      failure = e;
      trace = s;
    });
    if (trace != null) debugPrint('Bgm 忽略异常: $failure');
  }

  /// 供测试关掉真实音频通道（不落盘、不影响状态机）。
  @visibleForTesting
  void debugDisableAudio() {
    _audioDead = true;
  }

  /// 供测试重置内存状态（不落盘）。
  @visibleForTesting
  void debugReset() {
    tracks.clear();
    _cur = -1;
    _playing = false;
    _loop = true;
    _volume = 0.6;
    _audioDead = false;
    try {
      _player?.dispose();
    } catch (_) {}
    _player = null;
    notifyListeners();
  }

  void disposeAll() {
    try {
      _player?.dispose();
    } catch (_) {}
    _player = null;
  }
}

/// 全局单例：播放列表与播放状态跨页面存活（网页端 BGM 也是全局的，切页不断）。
final Bgm bgm = Bgm();
