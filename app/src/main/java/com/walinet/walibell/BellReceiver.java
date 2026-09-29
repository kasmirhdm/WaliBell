package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.util.Log;
import java.util.*;
import java.text.SimpleDateFormat;

public class BellReceiver extends BroadcastReceiver {
    private static final String TAG = "WaliBellAlarm";

    @Override public void onReceive(Context c, Intent i){
        if(!LicenseManager.isLicensed(c)){
            cancelAll(c);
            return;
        }
        int raw=i.getIntExtra("raw",0);
        String uri=i.getStringExtra("uri");
        String alarmKey=i.getStringExtra("alarmKey");

        // The exact alarm is delivered to this BroadcastReceiver first.
        // This is the standard AlarmManager path: the receiver is awakened
        // by the system, then starts the foreground service. Android explicitly
        // allows an exact alarm to start a foreground service in the background.
        saveStatus(c,"alarm_broadcast_received",
            "Alarm broadcast diterima " + nowText() + " • " +
            (i.getStringExtra("name")==null?"Bel":i.getStringExtra("name")) +
            " " + (i.getStringExtra("time")==null?"":i.getStringExtra("time")));

        // BellService owns de-duplication and audio playback. Keeping the
        // de-duplication in one place avoids rejecting the same alarm twice.
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

        // Keep the recurring school-bell schedule alive by calculating
        // the next one-shot alarm after this delivery.
        try{
            rescheduleAll(c);
        }catch(Exception e){
            saveStatus(c,"last_error",
                "Gagal menjadwalkan alarm berikutnya: " + e.getClass().getSimpleName() + " - " + e.getMessage());
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
                    int id=Integer.parseInt(p[0]);
                    for(int d=0;d<7;d++){
                        Intent in=new Intent(c,BellReceiver.class);
                        in.setAction("com.walinet.walibell.BELL_"+id+"_"+d);
                        PendingIntent pi=PendingIntent.getBroadcast(c,id*10+d,in,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
                        if(pi!=null){am.cancel(pi);pi.cancel();}
                        Intent backup=new Intent(c,BellReceiver.class);
                        backup.setAction("com.walinet.walibell.BELL_BACKUP_"+id+"_"+d);
                        PendingIntent bp=PendingIntent.getBroadcast(c,id*10+d+200000,backup,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
                        if(bp!=null){am.cancel(bp);bp.cancel();}
                    }
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
        if(!data.isEmpty()){
            // Cancel every previously scheduled weekday instance.
            for(String line:data.split("\\n")){
                try{
                    String[] p=line.split("\\|",-1);
                    if(p.length<6)continue;
                    int id=Integer.parseInt(p[0]);
                    for(int d=0;d<7;d++){
                        Intent old=new Intent(c,BellReceiver.class);
                        old.setAction("com.walinet.walibell.BELL_"+id+"_"+d);
                        PendingIntent pi;
                        if(android.os.Build.VERSION.SDK_INT>=26){
                            pi=PendingIntent.getBroadcast(
                                c,id*10+d,old,
                                PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                            );
                        }else{
                            pi=PendingIntent.getBroadcast(
                                c,id*10+d,old,
                                PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                            );
                        }
                        if(pi!=null){
                            am.cancel(pi);
                            pi.cancel();
                        }
                        Intent backupOld=new Intent(c,BellReceiver.class);
                        backupOld.setAction("com.walinet.walibell.BELL_BACKUP_"+id+"_"+d);
                        PendingIntent backupPi;
                        if(android.os.Build.VERSION.SDK_INT>=26){
                            backupPi=PendingIntent.getBroadcast(
                                c,id*10+d+200000,backupOld,
                                PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                            );
                        }else{
                            backupPi=PendingIntent.getBroadcast(
                                c,id*10+d+200000,backupOld,
                                PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                            );
                        }
                        if(backupPi!=null){
                            am.cancel(backupPi);
                            backupPi.cancel();
                        }

                        // Also cancel legacy backup broadcasts.
                        Intent legacyBackupOld=new Intent(c,BellReceiver.class);
                        legacyBackupOld.setAction("com.walinet.walibell.BELL_BACKUP_"+id+"_"+d);
                        PendingIntent legacyBackupPi=PendingIntent.getBroadcast(
                            c,id*10+d+200000,legacyBackupOld,
                            PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                        );
                        if(legacyBackupPi!=null){
                            am.cancel(legacyBackupPi);
                            legacyBackupPi.cancel();
                        }
                    }
                }catch(Exception e){
                    Log.w(TAG,"Gagal membatalkan alarm lama",e);
                }
            }
        }
        if(data.isEmpty())return;

        // IMPORTANT: no set(), setInexact(), setAndAllowWhileIdle(), or
        // repeating/inexact alarm is used here. Each schedule is an exact,
        // one-shot alarm. The next occurrence is scheduled after it fires.
        for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length<6)continue;

                int id=Integer.parseInt(p[0]);
                String time=p[1], uri=p[4];
                int raw=Integer.parseInt(p[5]);
                int daysMask=p.length>=7?Integer.parseInt(p[6]):31;
                if(daysMask==0)daysMask=31;

                String[] hm=time.split(":");
                if(hm.length!=2)continue;
                int hour=Integer.parseInt(hm[0]);
                int minute=Integer.parseInt(hm[1]);

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

                if(best==null)continue;

                int dayIndex=(best.get(Calendar.DAY_OF_WEEK)+5)%7;
                int requestCode=id*10+dayIndex;

                Intent in=new Intent(c,BellReceiver.class);
                in.setAction("com.walinet.walibell.BELL_"+id+"_"+dayIndex);
                in.putExtra("raw",raw);
                in.putExtra("uri",uri);
                in.putExtra("name",p[2]);
                in.putExtra("time",p[1]);
                String alarmKey=id+"_"+best.getTimeInMillis();
                in.putExtra("alarmKey",alarmKey);

                // IMPORTANT: AlarmManager delivers the exact alarm to the
                // BroadcastReceiver. The receiver then starts BellService as
                // a foreground service. This is the documented Android alarm
                // architecture and is more reliable across OEM/Android 15
                // implementations than using a Service PendingIntent directly.
                PendingIntent pi=PendingIntent.getBroadcast(
                    c,requestCode,in,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
                );

                saveStatus(c,"next_alarm",
                    p[2]+" "+p[1]+" • "+new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(best.getTime()));

                if(android.os.Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()){
                    Log.e(TAG,"Exact alarm permission BELUM diberikan; alarm tidak dijadwalkan: "+p[2]+" "+p[1]);
                    notifyExactAlarmPermissionRequired(c);
                    continue;
                }

                /*
                 * PRIMARY PATH: setAlarmClock().
                 *
                 * Android documents setAlarmClock() as the most critical
                 * exact-alarm API: the system does not adjust its delivery
                 * time and may leave low-power mode to deliver it.
                 */
                Intent showIntent=new Intent(c,MainActivity.class);
                showIntent.setAction("com.walinet.walibell.OPEN_FROM_ALARM");
                PendingIntent showPi=PendingIntent.getActivity(
                    c,requestCode+100000,showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
                );
                AlarmManager.AlarmClockInfo info=
                    new AlarmManager.AlarmClockInfo(best.getTimeInMillis(),showPi);

                // PRIMARY: AlarmClock is the most visible exact alarm and may
                // wake the device from low-power idle.
                try{
                    am.setAlarmClock(info,pi);
                    Log.i(TAG,"PRIMARY AlarmClock terjadwal: "+p[2]+" "+p[1]+" -> "+best.getTime());
                }catch(SecurityException e){
                    Log.e(TAG,"AlarmClock ditolak; mencoba backup setExactAndAllowWhileIdle",e);
                }

                // BACKUP: use a separate PendingIntent so the fallback can
                // coexist with the primary. BellReceiver de-duplicates both
                // deliveries using alarmKey.
                Intent backupIn=new Intent(c,BellReceiver.class);
                backupIn.setAction("com.walinet.walibell.BELL_BACKUP_"+id+"_"+dayIndex);
                backupIn.putExtra("raw",raw);
                backupIn.putExtra("uri",uri);
                backupIn.putExtra("name",p[2]);
                backupIn.putExtra("time",p[1]);
                backupIn.putExtra("alarmKey",alarmKey);
                PendingIntent backupPi=PendingIntent.getBroadcast(
                    c,requestCode+200000,backupIn,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
                );
                try{
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,best.getTimeInMillis(),backupPi
                    );
                    Log.i(TAG,"BACKUP ExactAndAllowWhileIdle terjadwal: "+p[2]+" "+p[1]);
                }catch(SecurityException e){
                    Log.e(TAG,"Backup exact alarm ditolak",e);
                }

                Log.i(TAG,"EXACT ALARM selesai dijadwalkan: "+p[2]+" "+p[1]+" -> "+best.getTime());
            }catch(SecurityException e){
                Log.e(TAG,"SecurityException saat menjadwalkan exact alarm",e);
            }catch(Exception e){
                Log.e(TAG,"Gagal menjadwalkan exact alarm",e);
            }
        }
    }
}
