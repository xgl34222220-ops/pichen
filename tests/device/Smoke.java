package bichen.devicecheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** Runs the real installed preview. No Root framework is installed or simulated. */
public final class Smoke extends Instrumentation {
    private int checks;
    private StringBuilder log=new StringBuilder();
    private Context target;
    private Activity activity;
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    private void check(boolean value,String detail) {
        if(!value)throw new AssertionError(detail);
        checks++;log.append("PASS ").append(detail).append('\n');
    }
    private static String sha(File file) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) { byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n); }
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();
    }
    private void call(String name) throws Exception {
        Method m=activity.getClass().getDeclaredMethod(name);m.setAccessible(true);
        runOnMainSync(()->{try{m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();SystemClock.sleep(400);
    }
    private String screen() {
        StringBuilder b=new StringBuilder();collect(getUiAutomation().getRootInActiveWindow(),b);return b.toString();
    }
    private boolean screenContains(String text) {
        for (int i=0;i<40;i++) { if(screen().contains(text))return true; SystemClock.sleep(100); }
        log.append("ACTIVE_WINDOW\n").append(screen()).append("END_WINDOW\n");return false;
    }
    private void collect(AccessibilityNodeInfo n,StringBuilder b) {
        if(n==null)return;if(n.getText()!=null)b.append(n.getText()).append('\n');
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),b);
    }
    private void shot(String name) throws Exception {
        SystemClock.sleep(500);Bitmap image=getUiAutomation().takeScreenshot();
        if(image!=null)try(FileOutputStream out=new FileOutputStream(new File(target.getFilesDir(),name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            getUiAutomation();SystemClock.sleep(500);
            target=getTargetContext();String pkg=target.getPackageName();
            check(pkg.equals("io.github.xgl34222220.bichen.preview"),"same preview package");
            check(target.getPackageManager().getPackageInfo(pkg,0).versionName.equals("0.3.0-test.3"),"installed test.3 version");
            Class<?> installer=Class.forName(pkg+".ModuleInstaller",true,target.getClassLoader());
            JSONObject info=(JSONObject)installer.getMethod("bundledInfo",Context.class).invoke(null,target);
            check(info.getString("version").equals("0.3.0-beta.1")&&info.getInt("versionCode")==301,"real embedded module metadata");
            File module=(File)installer.getMethod("bundledZip",Context.class).invoke(null,target);
            check(module.getName().equals("Bichen-0.3.0-beta.1-module.zip"),"export cache filename matches module version");
            check(sha(module).equals(info.getString("sha256")),"export cache matches embedded module hash");
            try(ZipFile z=new ZipFile(module)){check(z.getEntry("module.prop")!=null&&z.getEntry("bin/bichen")!=null,"export has flashable root entries");}
            check(sha((File)installer.getMethod("bundledZip",Context.class).invoke(null,target)).equals(info.getString("sha256")),"repeated export remains identical");
            Drawable icon=target.getResources().getDrawable(target.getApplicationInfo().icon,target.getTheme());
            Bitmap bitmap=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,108,108);icon.draw(new Canvas(bitmap));
            check(bitmap.getPixel(10,54)==0xff146b59,"original green launcher icon background");
            check(bitmap.getPixel(54,23)==0xffdff4e8,"original light shield artwork");
            try(FileOutputStream out=new FileOutputStream(new File(target.getFilesDir(),"restored-icon.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
            Intent launch=target.getPackageManager().getLaunchIntentForPackage(pkg);
            activity=startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            Field busy=activity.getClass().getDeclaredField("busy");busy.setAccessible(true);
            for(int i=0;i<400;i++){final boolean[] pending={true};runOnMainSync(()->{try{pending[0]=busy.getBoolean(activity);}catch(Exception e){throw new RuntimeException(e);}});if(!pending[0])break;SystemClock.sleep(100);}
            check(!activity.isFinishing(),"app starts without crash");
            Field nav=activity.getClass().getDeclaredField("nav");nav.setAccessible(true);
            String[] titles={"辟尘·测试","应用放行","过滤规则","请求活动"};
            for(int i=0;i<4;i++){
                final int index=i;runOnMainSync(()->{try{((ViewGroup)nav.get(activity)).getChildAt(index).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                waitForIdleSync();SystemClock.sleep(600);shot("page-"+i);check(screenContains(titles[i]),"page opens: "+titles[i]);
            }
            call("showModuleManager");
            shot("module-manager");check(screenContains("内置模块：0.3.0-beta.1"),"module sheet shows module rather than app version");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
            Method export=activity.getClass().getDeclaredMethod("exportFile",String.class);export.setAccessible(true);
            runOnMainSync(()->{try{export.invoke(activity,"module");}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(1500);
            shot("export-picker");check(screenContains("Bichen-0.3.0-beta.1-module.zip"),"system save picker receives correct module filename");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
            Object install=installer.getMethod("install",Context.class).invoke(null,target);
            int code=install.getClass().getField("code").getInt(install);
            check(code!=0,"without a Root framework install is not reported as success");
            log.append("BICHEN_DEVICE_PASS checks=").append(checks).append("\nNo real Root framework or OEM hardware tested.\n");
            result.putString("stream",log.toString());finish(Activity.RESULT_OK,result);
        } catch(Throwable e) { try{shot("failure");log.append("FAILURE_WINDOW\n").append(screen());}catch(Throwable ignored){} result.putString("stream",log+"\nBICHEN_DEVICE_FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result); }
    }
}
