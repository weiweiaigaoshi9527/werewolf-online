import 'dart:io' show File;
import 'dart:typed_data';

import 'package:flutter/painting.dart';

/// 桌面 / 移动：有本地绝对路径就用 [FileImage] 读原文件（[bytes] 在本平台不使用）。
ImageProvider? customBgImage({String? path, Uint8List? bytes}) =>
    (path == null || path.isEmpty) ? null : FileImage(File(path));
