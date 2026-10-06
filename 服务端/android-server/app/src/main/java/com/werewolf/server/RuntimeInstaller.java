package com.werewolf.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 运行环境安装器。
 *
 * 关键设计：应用私有目录 <code>/data/data/com.termux/files</code> 与内置的
 * Android 版 OpenJDK（来自 Termux 构建）中硬编码的 <code>TERMUX_PREFIX</code>
 * （<code>/data/data/com.termux/files/usr</code>）完全一致，因此该 JDK 的
 * DT_RUNPATH 可以直接解析，无需 patchelf 改写。
 */
public final class RuntimeInstaller {

    public static final String TAG = "WWServer";

    /** 运行时包版本；更换 assets 下的 bootstrap.zip 时必须 +1，App 会自动重新解包。 */
    public static final int BOOTSTRAP_VERSION = 1;

    public interface Progress {
        void onProgress(String message, int percent);
    }

    public interface Line {
        void on(String text);
    }

    private RuntimeInstaller() {
    }

    public static File filesDir(Context c) {
        return c.getFilesDir();
    }

    /** $PREFIX == /data/data/com.termux/files/usr */
    public static File prefix(Context c) {
        return new File(c.getFilesDir(), "usr");
    }

    public static File javaHome(Context c) {
        return new File(prefix(c), "lib/jvm/java-21-openjdk");
    }

    public static File javaBin(Context c) {
        return new File(javaHome(c), "bin/java");
    }

    public static File keytoolBin(Context c) {
        return new File(javaHome(c), "bin/keytool");
    }

    public static File homeDir(Context c) {
        return new File(c.getFilesDir(), "home");
    }

    public static File tmpDir(Context c) {
        return new File(prefix(c), "tmp");
    }

    /** 服务端工作目录：jar / data / config / uploads / logs 都在这里 */
    public static File serverDir(Context c) {
        return new File(c.getFilesDir(), "server");
    }

    public static File jarFile(Context c) {
        return new File(serverDir(c), "werewolf-online.jar");
    }

    public static File keystoreFile(Context c) {
        return new File(serverDir(c), "config/keystore.p12");
    }

    public static File logFile(Context c) {
        return new File(serverDir(c), "logs/server.log");
    }

    private static File marker(Context c) {
        return new File(serverDir(c), ".bootstrap.version");
    }

    public static boolean isReady(Context c) {
        return javaBin(c).exists() && jarFile(c).exists()
                && String.valueOf(BOOTSTRAP_VERSION).equals(readText(marker(c)).trim());
    }

    public static String installedVersion(Context c) {
        return readText(marker(c)).trim();
    }

    public static void ensureLayout(Context c) {
        File[] dirs = new File[]{
                serverDir(c),
                new File(serverDir(c), "config"),
                new File(serverDir(c), "data"),
                new File(serverDir(c), "uploads"),
                new File(serverDir(c), "logs"),
                homeDir(c),
                tmpDir(c),
        };
        for (File f : dirs) {
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.mkdirs();
            }
        }
    }

    /** 解包内置 JDK 运行时与 jar。调用前请自行在后台线程执行。 */
    public static void install(Context ctx, Progress p) throws IOException {
        ensureLayout(ctx);
        File files = filesDir(ctx);

        p.onProgress("清理旧运行时…", 1);
        deleteRecursively(prefix(ctx));

        long total = 0;
        String meta = readAssetString(ctx.getAssets(), "bootstrap.meta");
        for (String line : meta.split("\n")) {
            line = line.trim();
            if (line.startsWith("bytes=")) {
                try {
                    total = Long.parseLong(line.substring(6).trim());
                } catch (NumberFormatException ignore) {
                    total = 0;
                }
            }
        }
        if (total <= 0) {
            total = 1;
        }

        p.onProgress("解包 JDK 运行时…", 2);
        AssetManager am = ctx.getAssets();
        long written = 0;
        int lastPct = -1;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(am.open("bootstrap.zip", AssetManager.ACCESS_STREAMING), 1 << 16))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                File out = new File(files, name);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written += n;
                    }
                }
                int pct = (int) Math.min(88, 2 + written * 86 / total);
                if (pct != lastPct) {
                    lastPct = pct;
                    p.onProgress("解包 JDK 运行时 " + pct + "%", pct);
                }
            }
        }

        p.onProgress("设置可执行权限…", 90);
        chmodTree(prefix(ctx));

        p.onProgress("重建动态库链接…", 94);
        linkSymlinks(ctx);

        p.onProgress("安装服务端程序…", 97);
        copyAsset(am, "werewolf-online.jar", jarFile(ctx));

        p.onProgress("写入版本标记…", 99);
        writeText(marker(ctx), String.valueOf(BOOTSTRAP_VERSION));
        writeText(new File(serverDir(ctx), "README-运行时.txt"),
                "运行时目录由 狼人杀服务端 App 自动解包生成。\n"
                        + "$PREFIX = " + prefix(ctx).getAbsolutePath() + "\n"
                        + "JAVA_HOME = " + javaHome(ctx).getAbsolutePath() + "\n"
                        + "服务端工作目录 = " + serverDir(ctx).getAbsolutePath() + "\n");

        p.onProgress("完成", 100);
        Log.i(TAG, "runtime installed at " + prefix(ctx));
    }

    /** 依据 assets/symlinks.txt 重建 dpkg 符号链接（失败不致命，构建期已对关键库做了实体化）。 */
    private static void linkSymlinks(Context ctx) throws IOException {
        String manifest = readAssetString(ctx.getAssets(), "symlinks.txt");
        File files = filesDir(ctx);
        for (String raw : manifest.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String rel = line.substring(0, tab).trim();
            String target = line.substring(tab + 1).trim();
            if (rel.isEmpty() || target.isEmpty()) {
                continue;
            }
            File link = new File(files, rel);
            if (link.exists()) {
                continue;
            }
            File parent = link.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try {
                Os.symlink(target, link.getAbsolutePath());
            } catch (ErrnoException ex) {
                Log.w(TAG, "symlink failed " + rel + " -> " + target + " : " + ex.getMessage());
            }
        }
    }

    private static void chmodTree(File root) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                try {
                    Os.chmod(f.getAbsolutePath(), 0755);
                } catch (Throwable ignore) {
                    // 目录权限失败不影响执行
                }
                chmodTree(f);
            } else {
                try {
                    Os.chmod(f.getAbsolutePath(), 0755);
                } catch (Throwable ignore) {
                    // 忽略
                }
            }
        }
    }

    static void copyAsset(AssetManager am, String assetName, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = new BufferedInputStream(am.open(assetName, AssetManager.ACCESS_STREAMING), 1 << 16);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    static String readAssetString(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(name, AssetManager.ACCESS_STREAMING)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) {
                for (File c : cs) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String readText(File f) {
        if (f == null || !f.exists()) {
            return "";
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    /** 列出给定目录下的全部文件（用于日志展示与排错）。 */
    public static List<String> listTop(File dir, int limit) {
        List<String> out = new ArrayList<>();
        File[] cs = dir.listFiles();
        if (cs == null) {
            return out;
        }
        for (int i = 0; i < cs.length && i < limit; i++) {
            out.add(cs[i].getName());
        }
        return out;
    }
}
