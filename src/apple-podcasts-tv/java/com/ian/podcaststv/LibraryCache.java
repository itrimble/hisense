package com.ian.podcaststv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** App-private bounded disk cache. No media files or Apple credentials. */
final class LibraryCache {
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean closed;
    LibraryCache(Context c) { dir = new File(c.getCacheDir(), "native-library"); dir.mkdirs(); }
    static String key(String s) {
        try { byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for(byte v:b) out.append(String.format(Locale.US,"%02x",v&255)); return out.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    synchronized String read(String name) {
        try { return new String(new AtomicFile(new File(dir,key(name))).readFully(), StandardCharsets.UTF_8); }
        catch(Exception e) { return ""; }
    }
    void write(String name, String data) { io.execute(() -> { put(key(name),data.getBytes(StandardCharsets.UTF_8)); trim(); }); }
    private synchronized void put(String name, byte[] data) {
        AtomicFile f=new AtomicFile(new File(dir,name)); FileOutputStream out=null;
        try { out=f.startWrite(); out.write(data); f.finishWrite(out); }
        catch(Exception e) { if(out!=null) f.failWrite(out); }
    }
    void image(String url, ImageView view) {
        view.setTag(url); view.setImageDrawable(null);
        if(url==null || url.isEmpty()) return;
        io.execute(() -> {
            Bitmap bitmap=null;
            try {
                URL u=new URL(url); String h=u.getHost();
                if(!"https".equals(u.getProtocol()) || !(h.endsWith(".mzstatic.com") || h.equals("twit.cachefly.net"))) return;
                File file=new File(dir,key("art:"+url));
                byte[] bytes;
                if(file.isFile()) bytes=new AtomicFile(file).readFully();
                else {
                    HttpURLConnection c=(HttpURLConnection)u.openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(false);
                    try {
                        if(c.getResponseCode()!=200) return;
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        try(InputStream in=c.getInputStream()) { byte[] b=new byte[8192]; int n;
                            while((n=in.read(b))!=-1) { if(out.size()+n>4*1024*1024) return; out.write(b,0,n); } }
                        bytes=out.toByteArray();
                    } finally { c.disconnect(); }
                }
                BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
                o.inSampleSize=Math.max(1,Math.max(o.outWidth,o.outHeight)/240); o.inJustDecodeBounds=false; o.inPreferredConfig=Bitmap.Config.RGB_565;
                bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
                if(bitmap!=null && !file.exists()) { ByteArrayOutputStream out=new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,85,out); put(file.getName(),out.toByteArray()); trim(); }
                file.setLastModified(System.currentTimeMillis());
            } catch(Exception ignored) { }
            final Bitmap result=bitmap; ui.post(() -> { if(!closed && url.equals(view.getTag())) view.setImageBitmap(result); });
        });
    }
    private synchronized void trim() {
        File[] files=dir.listFiles(); if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified)); long total=0;
        for(File f:files)total+=f.length();
        for(File f:files) { if(total<=32L*1024*1024)break; long n=f.length(); if(f.delete())total-=n; }
    }
    void close() { closed=true; io.shutdownNow(); }
}
