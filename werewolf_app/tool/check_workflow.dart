// CI 工作流自检：不跑真实构建，只做"花一次 CI 才能发现"的静态检查。
//   dart run tool/check_workflow.dart
//
// 检查项：
//  1. workflow 里引用的每个仓库路径确实存在（防止改名后 CI 才 404）；
//  2. meta job 抽版本号的正则，对当前 pubspec.yaml 能算出合法版本；
//  3. quality job 跑的是整个离线套件（不允许退化成只列几个文件）；
//  4. 三端 job 齐全，且 release 依赖了三端。
// ignore_for_file: avoid_print

import 'dart:io';

const wf = '.github/workflows/client-release.yml';

void main() {
  final errors = <String>[];

  final f = File(wf);
  if (!f.existsSync()) {
    print('缺少 $wf');
    exitCode = 1;
    return;
  }
  final text = f.readAsStringSync();

  // 1) 引用的仓库路径必须存在。
  //    只认「带斜杠的目录路径」或「已知文件名」，否则会误伤 apt 包名
  //    （libgtk-3-dev / libstdc++-12-dev）与 runner、artifact 名（windows-latest、linux-x64）。
  final pathRe = RegExp(r'(?<![\w./-])((?:lib|test|tool|packaging|android|windows|linux)/[\w./-]*|pubspec\.yaml|\.github/workflows/[\w.-]+)');
  final referenced = <String>{
    for (final m in pathRe.allMatches(text))
      m.group(1)!.replaceAll(RegExp(r'[.,;]+$'), ''),
  };
  // 构建产物路径（CI 里现生成的）不参与检查
  const generated = ['build/windows', 'build/app', 'build/linux'];
  for (final p in referenced.toList()..sort()) {
    if (generated.any((g) => p.startsWith(g))) continue;
    if (!File(p).existsSync() && !Directory(p).existsSync()) {
      errors.add('workflow 引用了不存在的路径：$p');
    }
  }

  // 2) 版本号提取：与 meta job 里那条 sed 等价
  final pubspec = File('pubspec.yaml').readAsStringSync();
  final vm = RegExp(r'^version:\s*([0-9.]+)\+([0-9]+)', multiLine: true).firstMatch(pubspec);
  if (vm == null) {
    errors.add('pubspec.yaml 的 version 不是「x.y.z+n」格式，meta job 的 sed 会抽出空值');
  } else {
    print('版本号：name=${vm.group(1)} build=${vm.group(2)}');
  }

  // 3) quality 必须跑全量离线套件
  final quality = _jobBody(text, 'quality');
  if (quality == null) {
    errors.add('找不到 quality job');
  } else if (!RegExp(r'flutter test(?!\s+test/\S+ )').hasMatch(quality) || quality.contains('flutter test test/')) {
    errors.add('quality job 不应只跑指定的几个测试文件，要跑整个离线套件（live 会自动 skip）');
  } else if (!quality.contains('flutter analyze')) {
    errors.add('quality job 缺 flutter analyze');
  }

  // 4) 三端与 release 依赖
  for (final job in ['meta', 'quality', 'windows', 'linux', 'android', 'release']) {
    if (!RegExp('^  $job:', multiLine: true).hasMatch(text)) errors.add('缺少 job：$job');
  }
  final release = _jobBody(text, 'release');
  if (release != null) {
    for (final need in ['windows', 'linux', 'android']) {
      if (!release.contains(need)) errors.add('release job 应依赖 $need');
    }
  }

  // 5) 测试文件都要被 CI 覆盖到（即都在 test/ 下且能通过 flutter test 发现）
  final testDir = Directory('test');
  final files = testDir
      .listSync(recursive: true)
      .whereType<File>()
      .where((e) => e.path.endsWith('_test.dart'))
      .toList();
  print('离线套件将执行 ${files.length} 个测试文件：${files.map((e) => e.uri.pathSegments.last).join(', ')}');
  if (files.length < 6) errors.add('test/ 下测试文件异常偏少（${files.length}），检查是否被误删');

  if (errors.isEmpty) {
    print('OK 工作流自检通过');
    return;
  }
  print('!! 工作流自检发现问题：');
  for (final e in errors) {
    print('  - $e');
  }
  exitCode = 1;
}

/// 粗略取出某个 job 的正文（从 "  <name>:" 到下一个同缩进 key）。
String? _jobBody(String text, String name) {
  final lines = text.split('\n');
  final start = lines.indexWhere((l) => l == '  $name:');
  if (start < 0) return null;
  final buf = <String>[];
  for (var i = start + 1; i < lines.length; i++) {
    final l = lines[i];
    if (RegExp(r'^  \S').hasMatch(l)) break;
    buf.add(l);
  }
  return buf.join('\n');
}
