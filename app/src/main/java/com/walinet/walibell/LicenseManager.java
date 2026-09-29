package com.walinet.walibell;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class LicenseManager {
    private static final String SUPABASE_URL = "https://lfcecurviullknwsejgi.supabase.co";
    private static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_hxHQr4DjHk5z4IjuDm2i2Q_md0te6F3";
    private static final String ENDPOINT = SUPABASE_URL + "/functions/v1/activate-license";

    private static final String PREF = "walibell_license";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_CODE = "license_code";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_EXPIRES = "expires_at";

    private LicenseManager(){}

    public static boolean isLicensed(Context context){
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVATED, false);
    }

    public static String getLicenseCode(Context context){
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CODE, "");
    }

    public static String getDeviceId(Context context){
        SharedPreferences p=context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String id=p.getString(KEY_DEVICE, null);
        if(id!=null && !id.trim().isEmpty()) return id;

        // Use Android's app-scoped stable device identifier instead of a random UUID.
        // This keeps the same device identity across app reinstalls for the same
        // Android device/signing identity, while different devices get different IDs.
        String androidId=android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
        if(androidId==null || androidId.trim().isEmpty()){
            androidId=UUID.randomUUID().toString();
        }else{
            androidId="ANDROID-"+androidId;
        }
        p.edit().putString(KEY_DEVICE,androidId).apply();
        return androidId;
    }

    public static String getExpiresAt(Context context){
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_EXPIRES, "");
    }

    public interface Callback {
        void onResult(boolean success, String message, String expiresAt);
    }

    public static void activate(Context context, String code, Callback callback){
        final Context app=context.getApplicationContext();
        final String normalized=code==null?"":code.trim().toUpperCase();
        new Thread(() -> {
            boolean success=false;
            String message="Tidak dapat menghubungi server lisensi.";
            String expiresAt="";
            HttpURLConnection conn=null;
            try{
                URL url=new URL(ENDPOINT);
                conn=(HttpURLConnection)url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                conn.setRequestProperty("Accept","application/json");
                conn.setRequestProperty("apikey",SUPABASE_PUBLISHABLE_KEY);

                String json="{\"license_code\":\""+escape(normalized)+"\",\"device_id\":\""+escape(getDeviceId(app))+"\"}";
                try(OutputStream os=conn.getOutputStream()){
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int status=conn.getResponseCode();
                InputStream stream=status>=400?conn.getErrorStream():conn.getInputStream();
                String body=read(stream);
                success=status>=200 && status<300 && body.contains("\"success\":true");
                String serverMessage=extract(body,"message");
                String serverExpires=extract(body,"expires_at");
                if(!serverMessage.isEmpty())message=serverMessage;
                else if(success)message="Lisensi berhasil diaktifkan.";
                if(!serverExpires.isEmpty())expiresAt=serverExpires;

                if(success){
                    app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_ACTIVATED,true)
                        .putString(KEY_CODE,normalized)
                        .putString(KEY_EXPIRES,expiresAt)
                        .apply();
                }
            }catch(Exception e){
                message="Koneksi lisensi gagal. Periksa internet lalu coba lagi.";
            }finally{
                if(conn!=null)conn.disconnect();
            }

            final boolean resultSuccess=success;
            final String resultMessage=message;
            final String resultExpires=expiresAt;
            android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> callback.onResult(resultSuccess,resultMessage,resultExpires));
        }).start();
    }

    private static String escape(String s){
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }

    private static String read(InputStream in) throws Exception{
        if(in==null)return "";
        StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String line;
            while((line=r.readLine())!=null)b.append(line);
        }
        return b.toString();
    }

    private static String extract(String json,String key){
        String marker="\""+key+"\":";
        int i=json.indexOf(marker);
        if(i<0)return "";
        int start=i+marker.length();
        while(start<json.length() && Character.isWhitespace(json.charAt(start)))start++;
        if(start>=json.length())return "";
        if(json.charAt(start)=='"'){
            start++;
            StringBuilder b=new StringBuilder();
            boolean esc=false;
            for(int j=start;j<json.length();j++){
                char ch=json.charAt(j);
                if(esc){b.append(ch);esc=false;continue;}
                if(ch=='\\'){esc=true;continue;}
                if(ch=='"')return b.toString();
                b.append(ch);
            }
        }
        return "";
    }
}
