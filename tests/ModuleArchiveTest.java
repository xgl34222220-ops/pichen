package io.github.xgl34222220.bichen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ModuleArchiveTest {
    private static int checks;
    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : new String[]{"customize.sh","post-fs-data.sh","service.sh","uninstall.sh",
                "bin/bichen","bin/boot-runner.sh","bin/runtime.sh","bin/parse-domains.awk",
                "sources.tsv","rules/checksums.sha256","rules/adaway.txt","rules/china.txt",
                "rules/tracking.txt","rules/hagezi.txt"}) files.put(name, new byte[]{35});
        files.put("module.prop", bytes("id=bichen\nname=辟尘\nversion=0.3.0-beta.1\nversionCode=301\n"));
        return files;
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static File zip(Map<String, byte[]> files) throws IOException {
        File file=File.createTempFile("bichen-archive-test-", ".zip");
        try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, byte[]> e: files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();
            }
        }
        return file;
    }
    private static void run(Map<String, byte[]> files, String version, int code, String reason) throws Exception {
        File file=zip(files);
        try {
            try { ModuleArchive.verify(file,version,code);if(reason!=null)throw new AssertionError("Accepted: "+reason); }
            catch(IOException e) { if(reason==null||!e.getMessage().contains(reason))throw new AssertionError("Unexpected rejection: "+e,e); }
            checks++;
        } finally { Files.deleteIfExists(file.toPath()); }
    }
    public static void main(String[] args) throws Exception {
        run(fixture(),"0.3.0-beta.1",301,null);
        Map<String,byte[]> f=fixture();f.put("module.prop",bytes("# 注释\r\nid=bichen\r\nversion=0.3.0-beta.1\r\nversionCode=301\r\n"));run(f,"0.3.0-beta.1",301,null);
        for(String name:new String[]{"module.prop","customize.sh","bin/runtime.sh","bin/boot-runner.sh","rules/checksums.sha256"}){
            f=fixture();f.remove(name);run(f,"0.3.0-beta.1",301,name.equals("module.prop")?"根目录缺少":"缺少必要文件");
        }
        f=fixture();f.put("service.sh",new byte[0]);run(f,"0.3.0-beta.1",301,"缺少必要文件");
        f=new LinkedHashMap<>();f.put("Bichen/module/module.prop",bytes("id=bichen"));run(f,"0.3.0-beta.1",301,"源码包");
        f=new LinkedHashMap<>();f.put("AndroidManifest.xml",new byte[]{1});run(f,"0.3.0-beta.1",301,"这是 APK");
        for(String name:new String[]{"../escape","/absolute","bin/../escape","bin/./escape","bin//escape","bin\\escape"}){
            f=fixture();f.put(name,new byte[]{1});run(f,"0.3.0-beta.1",301,"不安全路径");
        }
        f=fixture();f.put("module.prop",bytes("id=other\nversion=0.3.0-beta.1\nversionCode=301"));run(f,"0.3.0-beta.1",301,"标识或版本");
        run(fixture(),"wrong",301,"标识或版本");run(fixture(),"0.3.0-beta.1",302,"标识或版本");
        run(fixture(),"0.3.0-beta.1",0,"标识或版本");
        f=fixture();f.put("module.prop",bytes(new String(f.get("module.prop"),StandardCharsets.UTF_8)+"id=bichen\n"));run(f,"0.3.0-beta.1",301,"重复字段");
        f=fixture();f.put("module.prop",new byte[16385]);run(f,"0.3.0-beta.1",301,"超出大小限制");
        f=fixture();f.put("module.prop",bytes("not-a-property"));run(f,"0.3.0-beta.1",301,"格式错误");
        f=fixture();for(int i=0;i<512;i++)f.put("extra/"+i,new byte[]{1});run(f,"0.3.0-beta.1",301,"内容超出限制");
        File missing=new File(System.getProperty("java.io.tmpdir"),"absent-"+System.nanoTime()+".zip");
        try{ModuleArchive.verify(missing,"0.3.0-beta.1",301);throw new AssertionError("Missing accepted");}catch(IOException expected){checks++;}
        System.out.println("PASS: module archive "+checks+" checks");
    }
}
