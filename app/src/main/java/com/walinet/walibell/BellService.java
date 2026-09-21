package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

public class BellService extends Service {
    static final int NOTIFICATION_ID=1001;
    static final String CHANNEL_ID="walibell_running";
    MediaPlayer player;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26
            ?new Notification.Builder(this,CHANNEL_ID)
            :new Notification.Builder(this);
        Notification n=b.setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("WaliBell aktif")
            .setContentText("Jadwal bel sekolah berjalan di latar belakang")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build();
        startForeground(NOTIFICATION_ID,n);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&(intent.hasExtra("raw")||intent.hasExtra("uri"))){
            int raw=intent.getIntExtra("raw",0);
            String uri=intent.getStringExtra("uri");
            play(raw,uri);
        }
        return START_STICKY;
    }

    void play(int raw,String uri){
        try{
            if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
            player=raw!=0?MediaPlayer.create(this,raw):
                (uri!=null&&!uri.isEmpty()?MediaPlayer.create(this,Uri.parse(uri)):null);
            if(player!=null){
                player.setOnCompletionListener(p->{p.release();if(p==player)player=null;});
                player.setOnErrorListener((p,what,extra)->{try{p.release();}catch(Exception ignored){}if(p==player)player=null;return true;});
                player.start();
            }
        }catch(Exception ignored){}
    }

    void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"WaliBell berjalan",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Notifikasi permanen agar WaliBell tetap aktif di latar belakang");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
        super.onDestroy();
    }
}
