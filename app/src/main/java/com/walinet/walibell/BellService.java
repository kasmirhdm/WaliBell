package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class BellService extends Service {
    static final int NOTIFICATION_ID=1001;
    static final String CHANNEL_ID="walibell_running";
    private static final String TAG="WaliBellService";
    MediaPlayer player;
    PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        saveStatus("service_started", "Foreground service aktif " + nowText());

        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(
            this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b=Build.VERSION.SDK_INT>=26
            ?new Notification.Builder(this,CHANNEL_ID)
            :new Notification.Builder(this);

        Notification n=b.setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("WaliBell aktif")
            .setContentText("Jadwal bel sekolah berjalan di latar belakang")
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .setPriority(Notification.PRIORITY_MAX)
            .build();

        // Android 10+ requires the FGS type to be supplied at runtime.
        if(Build.VERSION.SDK_INT>=29){
            startForeground(
                NOTIFICATION_ID,n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        }else{
            startForeground(NOTIFICATION_ID,n);
        }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!LicenseManager.isLicensed(this)){
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        saveStatus("last_command", "Perintah service diterima " + nowText());
        if(intent!=null&&(intent.hasExtra("raw")||intent.hasExtra("uri"))){
            int raw=intent.getIntExtra("raw",0);
            String uri=intent.getStringExtra("uri");
            String alarmKey=intent.getStringExtra("alarmKey");

            // Primary and backup alarms may both fire. Deduplicate here too,
            // because API 26+ alarms now target this foreground service directly.
            if(alarmKey!=null&&!alarmKey.isEmpty()&&!BellReceiver.markAlarmDelivered(this,alarmKey)){
                saveStatus("last_command","Duplikat alarm diabaikan " + nowText());
                return START_STICKY;
            }

            BellReceiver.logHistory(this,intent.getStringExtra("name"),intent.getStringExtra("time"));
            saveStatus("alarm_received","Alarm diterima " + nowText());
            play(raw,uri);

            // The BroadcastReceiver schedules the next one-shot occurrence.
            // Keeping scheduling in one component avoids two simultaneous
            // reschedule passes when primary and backup alarms fire.
        }
        return START_STICKY;
    }

    void acquireWakeLock(){
        try{
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm==null)return;
            if(wakeLock==null){
                wakeLock=pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WaliBell::BellPlayback"
                );
                wakeLock.setReferenceCounted(false);
            }
            if(!wakeLock.isHeld())wakeLock.acquire(90_000L);
        }catch(Exception e){
            saveStatus("last_error", "WakeLock gagal: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            Log.e(TAG,"Gagal memperoleh WakeLock",e);
        }
    }

    void releaseWakeLock(){
        try{
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        }catch(Exception e){
            Log.e(TAG,"Gagal melepas WakeLock",e);
        }
    }

    void play(int raw,String uri){
        try{
            acquireWakeLock();
            // Service sudah memegang WakeLock sendiri; bridge tidak diperlukan lagi.
            BellReceiver.releaseBridgeLock();

            if(player!=null){
                try{player.stop();}catch(Exception ignored){}
                try{player.release();}catch(Exception ignored){}
                player=null;
            }

            player=raw!=0
                ?MediaPlayer.create(this,raw)
                :(uri!=null&&!uri.isEmpty()?MediaPlayer.create(this,Uri.parse(uri)):null);

            if(player!=null){
                if(Build.VERSION.SDK_INT>=21){
                    try{
                        player.setAudioAttributes(
                            new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        );
                    }catch(Exception ignored){}
                }

                player.setOnCompletionListener(p->{
                    try{p.release();}catch(Exception ignored){}
                    if(p==player)player=null;
                    releaseWakeLock();
                });
                player.setOnErrorListener((p,what,extra)->{
                    try{p.release();}catch(Exception ignored){}
                    if(p==player)player=null;
                    releaseWakeLock();
                    Log.e(TAG,"MediaPlayer error what="+what+" extra="+extra);
                    return true;
                });
                player.start();
                saveStatus("audio_started", "Audio mulai diputar " + nowText());
            }else{
                saveStatus("last_error", "Audio tidak ditemukan " + nowText());
                Log.w(TAG,"Tidak ada audio untuk dimainkan");
                releaseWakeLock();
            }
        }catch(Exception e){
            releaseWakeLock();
            BellReceiver.releaseBridgeLock();
            saveStatus("last_error", "Gagal memutar bel: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            Log.e(TAG,"Gagal memutar bel",e);
        }
    }

    void saveStatus(String key,String value){
        try{
            getSharedPreferences("bell_status",0).edit().putString(key,value).apply();
        }catch(Exception ignored){}
    }

    String nowText(){
        return new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale.getDefault())
            .format(new java.util.Date());
    }

    void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            NotificationChannel ch=new NotificationChannel(
                CHANNEL_ID,
                "WaliBell berjalan",
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Notifikasi permanen agar WaliBell tetap aktif di latar belakang");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        if(player!=null){
            try{player.release();}catch(Exception ignored){}
            player=null;
        }
        releaseWakeLock();
        super.onDestroy();
    }
}
