#!/usr/bin/env python3
# ============================================================
#  狼人杀 Online · Linux 发行包重打包（仅替换包内 jar）
#
#  用法：
#    python repack-linux.py <基座.tar.gz> <输出.tar.gz> <新jar>
#
#  为什么用 Python 而不是 tar：
#    GNU tar 的 --delete/--append 就地增删在这类大包上会报 "Unexpected EOF in archive"；
#    而"解包到磁盘再重打"在 Windows 上必须创建符号链接（需要管理员权限），
#    本机拿不到——Linux 运行时恰好依赖 145 个符号链接。
#    Python 的 tarfile 把符号链接当作纯元数据（TarInfo.linkname），
#    不需要文件系统支持链接，因此可以逐成员原样复制、只把 jar 换成新的。
# ============================================================
import os
import sys
import tarfile

JAR_MEMBER = 'werewolf-server/werewolf-online.jar'


def main() -> int:
    if len(sys.argv) != 4:
        print('用法: repack-linux.py <基座.tar.gz> <输出.tar.gz> <新jar>', file=sys.stderr)
        return 2
    base, out, new_jar = sys.argv[1], sys.argv[2], sys.argv[3]

    for p in (base, new_jar):
        if not os.path.isfile(p):
            print('文件不存在: %s' % p, file=sys.stderr)
            return 2

    new_size = os.path.getsize(new_jar)
    new_mtime = int(os.path.getmtime(new_jar))

    stat = {'file': 0, 'dir': 0, 'sym': 0, 'hard': 0, 'other': 0}
    replaced = 0

    with tarfile.open(base, 'r:gz') as src, \
            tarfile.open(out, 'w:gz', format=tarfile.GNU_FORMAT, compresslevel=9) as dst:
        for member in src:
            if member.name == JAR_MEMBER:
                # 替换载荷：元数据沿用原成员，仅更新 size/mtime
                m = tarfile.TarInfo(JAR_MEMBER)
                m.size = new_size
                m.mode = member.mode
                m.uid, m.gid = member.uid, member.gid
                m.uname, m.gname = member.uname, member.gname
                m.mtime = new_mtime
                with open(new_jar, 'rb') as fh:
                    dst.addfile(m, fh)
                replaced += 1
                continue

            if member.isreg():
                stat['file'] += 1
                with src.extractfile(member) as fh:
                    dst.addfile(member, fh)
            elif member.isdir():
                stat['dir'] += 1
                dst.addfile(member)
            elif member.issym():
                # 符号链接：只写元数据，不碰文件系统
                stat['sym'] += 1
                dst.addfile(member)
            elif member.islnk():
                stat['hard'] += 1
                dst.addfile(member)
            else:
                stat['other'] += 1
                dst.addfile(member)

    if replaced != 1:
        print('错误：包内 %s 出现 %d 次（期望 1 次）' % (JAR_MEMBER, replaced), file=sys.stderr)
        return 1

    print('      常规文件 %d  目录 %d  符号链接 %d  硬链接 %d  其它 %d'
          % (stat['file'], stat['dir'], stat['sym'], stat['hard'], stat['other']))
    print('      已替换 jar: %d 字节' % new_size)
    return 0


if __name__ == '__main__':
    sys.exit(main())
