package io.github.xgl34222220.bichen;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Installs the bundled archive exclusively through the current framework's official CLI. */
public final class ModuleInstaller {
    private static final long INSTALL_TIMEOUT_MS = 180_000L;
    private static final long MAX_BUNDLE_BYTES = 128L * 1024L * 1024L;
    private static final String ASSET_ZIP = "bichen-module.zip";
    private static final String EXPORT_HINT = "请导出内置模块 ZIP，再使用你当前的 Root 管理器安装。";

    private ModuleInstaller() { }

    public static JSONObject bundledInfo(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("module-info.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2_048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > 32_768) throw new IOException("内置模块元数据过大");
                bytes.write(buffer, 0, read);
            }
            JSONObject info = RootBridge.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!info.optString("sha256").matches("[a-fA-F0-9]{64}")) {
                throw new IOException("内置模块缺少有效 SHA-256，已停止安装");
            }
            if (!info.optString("version").matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IOException("内置模块版本信息无效");
            }
            if (!ASSET_ZIP.equals(info.optString("file", ASSET_ZIP))) {
                throw new IOException("内置模块文件名与元数据不匹配");
            }
            return info;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("无法读取内置模块信息：" + error.getMessage(), error);
        }
    }

    /** The returned file has already passed a streaming SHA-256 check. Call on a worker. */
    public static synchronized File bundledZip(Context context) throws IOException {
        RootBridge.requireWorkerThread();
        JSONObject info = bundledInfo(context);
        String expected = info.optString("sha256").toLowerCase(Locale.ROOT);
        File directory = new File(context.getCacheDir(), "exports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建模块导出目录");
        File temporary = File.createTempFile("bichen-verified-", ".zip", directory);
        boolean completed = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = context.getAssets().open(ASSET_ZIP);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65_536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BUNDLE_BYTES) throw new IOException("内置模块超出 128 MiB 限制");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            String actual = hex(digest.digest());
            if (total == 0 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("内置模块完整性校验失败，已停止安装");
            }
            ModuleArchive.verify(temporary, info.optString("version"), info.optInt("versionCode", -1));
            File destination = new File(directory, "Bichen-" + info.optString("version") + "-module.zip");
            if (!temporary.renameTo(destination)) throw new IOException("无法保存已校验的模块包");
            completed = true;
            return destination;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("设备不支持 SHA-256 校验", impossible);
        } finally {
            if (!completed) temporary.delete();
        }
    }

    public static RootBridge.Result install(Context context) {
        RootBridge.requireWorkerThread();
        final File zip;
        final JSONObject info;
        try {
            info = bundledInfo(context);
            zip = bundledZip(context);
        } catch (IOException error) {
            return new RootBridge.Result(65, "无法准备内置模块：" + error.getMessage());
        }
        String expected = info.optString("sha256").toLowerCase(Locale.ROOT);
        String stage = "/data/adb/bichen-install-" + UUID.randomUUID().toString();
        String command = "umask 077\n"
                + "if [ \"$(id -u)\" != 0 ]; then printf '%s\\n' '没有获得 Root 权限，请在当前 Root 管理器中授权辟尘'; exit 126; fi\n"
                // Presence alone does not pick a stale second framework. Verify module subcommands.
                + "bc_ksu=; bc_ap=; bc_magisk=; bc_count=0\n"
                + "for bc_path in /data/adb/ksud /data/adb/ksu/bin/ksud; do\n"
                + "  if [ -x \"$bc_path\" ] && \"$bc_path\" module --help >/dev/null 2>&1; then bc_ksu=$bc_path; bc_count=$((bc_count+1)); break; fi\ndone\n"
                + "for bc_path in /data/adb/apd /data/adb/ap/bin/apd; do\n"
                + "  if [ -x \"$bc_path\" ] && \"$bc_path\" module --help >/dev/null 2>&1; then bc_ap=$bc_path; bc_count=$((bc_count+1)); break; fi\ndone\n"
                + "bc_path=$(command -v magisk 2>/dev/null)\n"
                + "if [ -n \"$bc_path\" ] && [ -x \"$bc_path\" ] && \"$bc_path\" -v >/dev/null 2>&1; then bc_magisk=$bc_path; fi\n"
                + "if [ -z \"$bc_magisk\" ]; then\n"
                + "  for bc_path in /data/adb/magisk/magisk /sbin/magisk /debug_ramdisk/magisk; do\n"
                + "    if [ -x \"$bc_path\" ] && \"$bc_path\" -v >/dev/null 2>&1; then bc_magisk=$bc_path; break; fi\n  done\nfi\n"
                + "[ -z \"$bc_magisk\" ] || bc_count=$((bc_count+1))\n"
                + "bc_framework=; bc_suver=$(su -v 2>/dev/null)\n"
                + "case \"$bc_suver\" in\n"
                + "  *MAGISK*|*Magisk*|*magisk*) [ -z \"$bc_magisk\" ] || bc_framework=Magisk;;\n"
                + "  *APatch*|*APATCH*|*apatch*) [ -z \"$bc_ap\" ] || bc_framework=APatch;;\n"
                + "  *KernelSU*|*KERNELSU*|*kernelsu*|*ReSukSU*|*ReSukiSU*|*SukiSU*) [ -z \"$bc_ksu\" ] || bc_framework=KernelSU;;\nesac\n"
                + "if [ -z \"$bc_framework\" ] && [ \"$bc_count\" -eq 1 ]; then\n"
                + "  if [ -n \"$bc_ksu\" ]; then bc_framework=KernelSU; elif [ -n \"$bc_ap\" ]; then bc_framework=APatch; else bc_framework=Magisk; fi\nfi\n"
                + "if [ -z \"$bc_framework\" ]; then\n"
                + "  if [ \"$bc_count\" -gt 1 ]; then printf '%s\\n' '检测到多个 Root 框架的残留安装入口，无法确定当前框架。'; "
                + "else printf '%s\\n' '当前 Root 已授权，但未检测到可验证的官方模块安装命令。'; fi\n"
                + "  printf '%s\\n' " + RootBridge.quote(EXPORT_HINT) + "; exit 69\nfi\n"
                // Stage before invoking installers that enter the init mount namespace (notably ksud).
                + "bc_stage=" + RootBridge.quote(stage) + "\n"
                + "mkdir \"$bc_stage\" || { printf '%s\\n' '无法创建 Root 私有安装目录'; exit 73; }\n"
                + "chmod 0700 \"$bc_stage\" || { rmdir \"$bc_stage\"; exit 73; }\n"
                + "bc_cleanup() { rm -f \"$bc_stage/module.zip\"; rmdir \"$bc_stage\" 2>/dev/null || :; }\n"
                + "trap bc_cleanup EXIT\ntrap 'exit 130' HUP INT TERM\n"
                + "cp " + RootBridge.quote(zip.getAbsolutePath()) + " \"$bc_stage/module.zip\" || { printf '%s\\n' '无法复制内置模块至 Root 安装目录'; exit 74; }\n"
                + "chmod 0600 \"$bc_stage/module.zip\" || exit 74\n"
                + "bc_hash=\n"
                + "if command -v sha256sum >/dev/null 2>&1; then bc_hash=$(sha256sum \"$bc_stage/module.zip\"); else\n"
                + "  for bc_bb in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do\n"
                + "    if [ -x \"$bc_bb\" ]; then bc_hash=$(\"$bc_bb\" sha256sum \"$bc_stage/module.zip\"); [ -z \"$bc_hash\" ] || break; fi\n  done\nfi\n"
                + "bc_hash=${bc_hash%% *}\n"
                + "if [ \"$bc_hash\" != " + RootBridge.quote(expected) + " ]; then printf '%s\\n' 'Root 安装副本 SHA-256 校验失败，已中止'; exit 65; fi\n"
                + "printf '内置模块 SHA-256 校验通过。\\n使用 %s 的官方安装命令：\\n' \"$bc_framework\"\n"
                + "case \"$bc_framework\" in\n"
                + "  KernelSU) \"$bc_ksu\" module install \"$bc_stage/module.zip\";;\n"
                + "  APatch) \"$bc_ap\" module install \"$bc_stage/module.zip\";;\n"
                + "  Magisk) \"$bc_magisk\" --install-module \"$bc_stage/module.zip\";;\n"
                + "esac\nbc_install_code=$?\n"
                + "if [ \"$bc_install_code\" -ne 0 ]; then printf '\\n官方安装命令失败，退出码：%s\\n' \"$bc_install_code\"; exit \"$bc_install_code\"; fi\n"
                + "printf '\\n%s\\n' '安装命令已成功完成。请重启手机，再回到辟尘检查挂载和拦截状态；本次安装尚不表示拦截已生效。'\n";
        RootBridge.Result result = RootBridge.rootShell(context, command, INSTALL_TIMEOUT_MS);
        if (result.code == 124) {
            return new RootBridge.Result(124, result.output
                    + "\n安装超时，尚不能确认结果。请先查看当前 Root 管理器的模块状态，避免重复安装。");
        }
        return result;
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] value = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            value[i * 2] = digits[(bytes[i] >>> 4) & 15];
            value[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(value);
    }
}
