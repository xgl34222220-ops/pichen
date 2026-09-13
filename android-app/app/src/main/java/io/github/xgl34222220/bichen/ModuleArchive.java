package io.github.xgl34222220.bichen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shared structural check for the bundled module: never executes or extracts scripts. */
public final class ModuleArchive {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "module.prop", "customize.sh", "post-fs-data.sh", "service.sh", "uninstall.sh",
            "bin/bichen", "bin/boot-runner.sh", "bin/runtime.sh", "bin/parse-domains.awk",
            "sources.tsv", "rules/checksums.sha256", "rules/adaway.txt", "rules/china.txt",
            "rules/tracking.txt", "rules/hagezi.txt"));

    private ModuleArchive() { }

    public static void verify(File file, String expectedVersion, int expectedCode) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_BYTES)
            throw new IOException("模块 ZIP 不存在、为空或超出大小限制");
        try (ZipFile zip = new ZipFile(file)) {
            Set<String> names = new HashSet<>();
            long expanded = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                        || name.contains("//") || Arrays.asList(name.split("/")).contains("..")
                        || Arrays.asList(name.split("/")).contains(".") || !names.add(name))
                    throw new IOException("模块 ZIP 含重复或不安全路径");
                if (names.size() > 512 || entry.getSize() < 0 || entry.getSize() > MAX_BYTES
                        || (expanded += entry.getSize()) > MAX_BYTES)
                    throw new IOException("模块 ZIP 内容超出限制");
            }
            if (!names.contains("module.prop")) {
                if (names.contains("AndroidManifest.xml"))
                    throw new IOException("这是 APK，不是模块 ZIP；请安装 APK 或导出内置模块");
                throw new IOException("这不是可直接刷入的辟尘模块：ZIP 根目录缺少 module.prop。源码包和构建合集不能刷入");
            }
            for (String name : REQUIRED) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() == 0)
                    throw new IOException("模块 ZIP 缺少必要文件：" + name);
            }
            Map<String, String> props = new HashMap<>();
            byte[] content = readSmall(zip.getInputStream(zip.getEntry("module.prop")));
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split < 1) throw new IOException("module.prop 内容格式错误");
                String key = line.substring(0, split).trim(), value = line.substring(split + 1).trim();
                if (props.put(key, value) != null) throw new IOException("module.prop 含重复字段：" + key);
            }
            if (!"bichen".equals(props.get("id")) || expectedVersion == null
                    || !expectedVersion.equals(props.get("version")) || expectedCode <= 0
                    || !Integer.toString(expectedCode).equals(props.get("versionCode")))
                throw new IOException("模块标识或版本与内置元数据不一致，已停止安装");
        }
    }

    private static byte[] readSmall(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 16384) throw new IOException("module.prop 超出大小限制");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
