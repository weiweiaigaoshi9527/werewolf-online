import 'dart:typed_data';

import 'package:flutter/painting.dart';

/// Web：浏览器不暴露本地文件路径（[path] 在本平台恒为空），只能用 file_picker
/// 回传的字节渲染。字节只留在内存里（base64 塞 localStorage 会顶爆浏览器配额），
/// 因此 Web 上的自定义背景不跨会话保留。
ImageProvider? customBgImage({String? path, Uint8List? bytes}) =>
    (bytes == null || bytes.isEmpty) ? null : MemoryImage(bytes);
