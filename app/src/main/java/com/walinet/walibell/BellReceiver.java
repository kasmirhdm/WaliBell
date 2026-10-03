package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.util.Log;
import android.os.PowerManager;
import java.util.*;
import java.text.SimpleDateFormat;

public class BellReceiver extends BroadcastReceiver {
    private static final String TAG = "WaliBellAlarm";

    // Bridge WakeLock: menjembatani jeda antara onReceive() selesai
    // (wake-lock broadcast dilepas oleh sistem) dan BellService.play()
    // memperoleh WakeLock-nya sendiri. Tanpa ini, pada HP yang terkunci /
    // Doze, CPU bisa tidur di jeda tersebut sehingga bel tidak bunyi.
    // Timeout 60 detik sebagai pengaman bila service gagal mengambil alih.
    private static PowerManager.WakeLock bridgeLock;

    static void acquireBridgeLock(Context c){
        try{
            PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
            if(pm==null)return;
            synchronized(BellReceiver.class){
                if(bridgeLock==null){
                    bridgeLock=pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,"WaliBell::AlarmBridge");
                    bridgeLock.setReferenceCounted(false);
                }
                if(!bridgeLock.isHeld())bridgeLock.acquire(60_000L);
            }
            Log.i(TAG,"Bridge WakeLock diperoleh (60 dtk)");
        }catch(Exception e){
            Log.e(TAG,"Gagal memperoleh bridge WakeLock",e);
        }
    }

    static void releaseBridgeLock(){
        try{
            synchronized(BellReceiver.class){
                if(bridgeLock!=null&&bridgeLock.isHeld())bridgeLock.release();
            }
        }catch(Exception e){
            Log.e(TAG,"Gagal melepas bridge WakeLock",e);
        }
    }

    @Override public void onReceive(Context c, Intent i){
        if(!LicenseManager.isLicensed(c)){
            cancelAll(c);
            return;
        }

        // Tahan CPU tetap menyala sampai BellService mengambil alih.
        // Kunci dilepas di BellService.play() (atau otomatis 60 dtk).
        acquireBridgeLock(c);

        int raw=i.getIntExtra("raw",0);
        String uri=i.getStringExtra("uri");
        String alarmKey=i.getStringExtra("alarmKey");
        int alarmId=i.getIntExtra("alarmId",-1);

        saveStatus(c,"alarm_broadcast_received",
            "Alarm broadcast diterima " + nowText() + " • " +
            (i.getStringExtra("name")==null?"Bel":i.getStringExtra("name")) +
            " " + (i.getStringExtra("time")==null?"":i.getStringExtra("time")));

        try{
            Intent service=new Intent(c,BellService.class);
            service.putExtra("raw",raw);
            service.putExtra("uri",uri);
            service.putExtra("name",i.getStringExtra("name"));
            service.putExtra("time",i.getStringExtra("time"));
            service.putExtra("alarmKey",alarmKey);
            if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(service);
            else c.startService(service);
        }catch(Exception e){
            saveStatus(c,"last_error",
                "BellService gagal dimulai: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            Log.e(TAG,"Gagal memulai BellService dari exact alarm",e);
        }

        // Penting: setelah satu jadwal berbunyi, jangan membatalkan dan
        // mendaftarkan ulang jadwal milik jadwal lain. Cukup jadwalkan
        // occurrence berikutnya untuk ID jadwal yang baru saja berbunyi.
        try{
            if(alarmId>=0) rescheduleOne(c,alarmId);
            else rescheduleAll(c); // kompatibilitas dengan alarm lama.
        }catch(Exception e){
            saveStatus(c,"last_error",
                "Gagal menjadwalkan alarm berikutnya: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            Log.e(TAG,"Gagal menjadwalkan alarm berikutnya",e);
        }
    }

    static boolean markAlarmDelivered(Context c,String key){
        android.content.SharedPreferences sp=c.getSharedPreferences("alarm_delivery",0);
        long now=System.currentTimeMillis();
        long last=sp.getLong(key,0);
        // Backup/primary delivery of the same scheduled occurrence is treated
        // as one event for up to 10 minutes.
        if(last>0 && now-last<10*60*1000L)return false;
        sp.edit().putLong(key,now).apply();
        return true;
    }

    static void saveStatus(Context c,String key,String value){
        try{
            c.getSharedPreferences("bell_status",0).edit().putString(key,value).apply();
        }catch(Exception ignored){}
    }

    static String nowText(){
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(new Date());
    }

    static void logHistory(Context c,String name,String time){
        String stamp=new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(new Date());
        String line=stamp+"  •  "+(name==null||name.isEmpty()?"Bel":name)+(time==null||time.isEmpty()?"":"  •  "+time);
        android.content.SharedPreferences sp=c.getSharedPreferences("history",0);
        String old=sp.getString("data","");
        String[] arr=old.isEmpty()?new String[0]:old.split("\\n");
        StringBuilder b=new StringBuilder();
        int start=Math.max(0,arr.length-99);
        for(int j=start;j<arr.length;j++){
            if(!arr[j].trim().isEmpty())b.append(arr[j]).append("\\n");
        }
        b.append(line).append("\\n");
        sp.edit().putString("data",b.toString()).apply();
    }

    static void notifyExactAlarmPermissionRequired(Context c){
        final String channelId="walibell_permission";
        final String channelName="WaliBell Permission";
        try{
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm==null)return;

            if(android.os.Build.VERSION.SDK_INT>=26){
                NotificationChannel ch=new NotificationChannel(
                    channelId,channelName,NotificationManager.IMPORTANCE_HIGH
                );
                ch.setDescription("Peringatan jika akses Alarm & Pengingat WaliBell belum aktif");
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }

            Intent settings=new Intent(
                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:"+c.getPackageName())
            );
            PendingIntent openSettings=PendingIntent.getActivity(
                c,99001,settings,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder b=android.os.Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(c,channelId)
                :new Notification.Builder(c);

            Notification n=b
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("Izin Alarm Diperlukan")
                .setContentText("WaliBell tidak dapat menjadwalkan bel. Ketuk untuk mengaktifkan izin Alarm & Pengingat.")
                .setStyle(new Notification.BigTextStyle().bigText(
                    "WaliBell tidak dapat menjadwalkan bel karena akses Alarm & Pengingat belum aktif. Ketuk notifikasi untuk membuka pengaturan."
                ))
                .setContentIntent(openSettings)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .build();

            nm.notify(99001,n);
        }catch(SecurityException e){
            Log.e(TAG,"Tidak dapat menampilkan notifikasi izin; kemungkinan izin notifikasi belum diberikan",e);
        }catch(Exception e){
            Log.e(TAG,"Gagal menampilkan notifikasi izin exact alarm",e);
        }
    }

    static void clearExactAlarmPermissionNotification(Context c){
        try{
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm!=null)nm.cancel(99001);
        }catch(Exception ignored){}
    }

    static int dayBit(int calendarDay){
        return 1 << ((calendarDay + 5) % 7);
    }

    static String actionFor(int id,int dayIndex){
        return "com.walinet.walibell.BELL_"+id+"_"+dayIndex;
    }

    static String backupActionFor(int id,int dayIndex){
        return "com.walinet.walibell.BELL_BACKUP_"+id+"_"+dayIndex;
    }

    static void cancelScheduleAlarms(Context c, AlarmManager am, int id){
        for(int d=0;d<7;d++){
            try{
                Intent in=new Intent(c,BellReceiver.class);
                in.setAction(actionFor(id,d));
                PendingIntent pi=PendingIntent.getBroadcast(
                    c,id*10+d,in,
                    PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                );
                if(pi!=null){ am.cancel(pi); pi.cancel(); }

                Intent backup=new Intent(c,BellReceiver.class);
                backup.setAction(backupActionFor(id,d));
                PendingIntent bp=PendingIntent.getBroadcast(
                    c,id*10+d+200000,backup,
                    PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                );
                if(bp!=null){ am.cancel(bp); bp.cancel(); }
            }catch(Exception e){
                Log.w(TAG,"Gagal membatalkan alarm jadwal id="+id+" hari="+d,e);
            }
        }
    }

    static boolean scheduleNextForId(Context c, AlarmManager am, int id){
        String data=c.getSharedPreferences("schedule",0).getString("data","");
        if(data.isEmpty())return false;

        String[] target=null;
        for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length>=6 && Integer.parseInt(p[0])==id){
                    target=p;
                    break;
                }
            }catch(Exception ignored){}
        }
        if(target==null)return false;

        try{
            String time=target[1];
            String[] hm=time.split(":");
            if(hm.length!=2)return false;

            int hour=Integer.parseInt(hm[0]);
            int minute=Integer.parseInt(hm[1]);
            int raw=Integer.parseInt(target[5]);
            String uri=target[4];
            int daysMask=target.length>=7?Integer.parseInt(target[6]):31;
            if(daysMask==0)daysMask=31;

            Calendar now=Calendar.getInstance();
            Calendar best=null;

            for(int offset=0;offset<7;offset++){
                Calendar cal=(Calendar)now.clone();
                cal.add(Calendar.DAY_OF_YEAR,offset);

                int bit=dayBit(cal.get(Calendar.DAY_OF_WEEK));
                if((daysMask&bit)==0)continue;

                cal.set(Calendar.HOUR_OF_DAY,hour);
                cal.set(Calendar.MINUTE,minute);
                cal.set(Calendar.SECOND,0);
                cal.set(Calendar.MILLISECOND,0);

                if(cal.getTimeInMillis()<=System.currentTimeMillis())continue;
                if(best==null||cal.before(best))best=cal;
            }

            if(best==null)return false;

            int dayIndex=(best.get(Calendar.DAY_OF_WEEK)+5)%7;
            int requestCode=id*10+dayIndex;
            String alarmKey=id+"_"+best.getTimeInMillis();

            Intent in=new Intent(c,BellReceiver.class);
            in.setAction(actionFor(id,dayIndex));
            in.putExtra("alarmId",id);
            in.putExtra("raw",raw);
            in.putExtra("uri",uri);
            in.putExtra("name",target[2]);
            in.putExtra("time",target[1]);
            in.putExtra("alarmKey",alarmKey);

            PendingIntent pi=PendingIntent.getBroadcast(
                c,requestCode,in,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
            );

            if(android.os.Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()){
                Log.e(TAG,"Exact alarm permission BELUM diberikan: "+target[2]+" "+target[1]);
                notifyExactAlarmPermissionRequired(c);
                return false;
            }

            Intent showIntent=new Intent(c,MainActivity.class);
            showIntent.setAction("com.walinet.walibell.OPEN_FROM_ALARM");
            PendingIntent showPi=PendingIntent.getActivity(
                c,requestCode+100000,showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager.AlarmClockInfo info=
                new AlarmManager.AlarmClockInfo(best.getTimeInMillis(),showPi);

            try{
                am.setAlarmClock(info,pi);
                Log.i(TAG,"PRIMARY AlarmClock terjadwal: "+target[2]+" "+target[1]+" -> "+best.getTime());
            }catch(SecurityException e){
                Log.e(TAG,"AlarmClock ditolak",e);
                return false;
            }

            Intent backupIn=new Intent(c,BellReceiver.class);
            backupIn.setAction(backupActionFor(id,dayIndex));
            backupIn.putExtra("alarmId",id);
            backupIn.putExtra("raw",raw);
            backupIn.putExtra("uri",uri);
            backupIn.putExtra("name",target[2]);
            backupIn.putExtra("time",target[1]);
            backupIn.putExtra("alarmKey",alarmKey);

            PendingIntent backupPi=PendingIntent.getBroadcast(
                c,requestCode+200000,backupIn,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
            );

            try{
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,best.getTimeInMillis(),backupPi
                );
                Log.i(TAG,"BACKUP ExactAndAllowWhileIdle terjadwal: "+target[2]+" "+target[1]);
            }catch(SecurityException e){
                Log.e(TAG,"Backup exact alarm ditolak",e);
            }

            saveStatus(c,"next_alarm",
                target[2]+" "+target[1]+" • "+
                new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(best.getTime()));

            Log.i(TAG,"EXACT ALARM selesai: "+target[2]+" "+target[1]+" -> "+best.getTime());
            return true;
        }catch(SecurityException e){
            Log.e(TAG,"SecurityException saat menjadwalkan id="+id,e);
            return false;
        }catch(Exception e){
            Log.e(TAG,"Gagal menjadwalkan id="+id,e);
            return false;
        }
    }

    static void rescheduleOne(Context c,int id){
        if(!LicenseManager.isLicensed(c)){
            cancelAll(c);
            return;
        }
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null)return;

        // Hanya reset alarm milik jadwal ini.
        cancelScheduleAlarms(c,am,id);
        scheduleNextForId(c,am,id);
    }

    public static void cancelAll(Context c){
        try{
            AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
            if(am==null)return;
            String data=c.getSharedPreferences("schedule",0).getString("data","");
            if(data.isEmpty())return;

            for(String line:data.split("\\n")){
                try{
                    String[] p=line.split("\\|",-1);
                    if(p.length<6)continue;
                    cancelScheduleAlarms(c,am,Integer.parseInt(p[0]));
                }catch(Exception ignored){}
            }
        }catch(Exception ignored){}
    }

    public static void rescheduleAll(Context c){
        if(!LicenseManager.isLicensed(c)){
            cancelAll(c);
            return;
        }
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null)return;

        String data=c.getSharedPreferences("schedule",0).getString("data","");
        if(data.isEmpty())return;

        // Dipakai saat aplikasi dibuka, jadwal diedit/ditambah, boot, atau
        // izin alarm berubah. Semua jadwal boleh dibangun ulang pada kondisi
        // ini. Yang penting: saat satu alarm berbunyi, rescheduleOne() saja
        // yang dipanggil sehingga jadwal lain tidak disentuh.
        for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length<6)continue;
                cancelScheduleAlarms(c,am,Integer.parseInt(p[0]));
            }catch(Exception ignored){}
        }

        for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length<6)continue;
                scheduleNextForId(c,am,Integer.parseInt(p[0]));
            }catch(Exception ignored){}
        }
    }

}
