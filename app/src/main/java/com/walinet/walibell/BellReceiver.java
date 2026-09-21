package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.util.Log;
import java.util.*;
import java.text.SimpleDateFormat;

public class BellReceiver extends BroadcastReceiver {
    private static final String TAG = "WaliBellAlarm";

    @Override public void onReceive(Context c, Intent i){
        int raw=i.getIntExtra("raw",0);
        String uri=i.getStringExtra("uri");
        logHistory(c,i.getStringExtra("name"),i.getStringExtra("time"));

        // The exact alarm itself is the background trigger. Starting the
        // foreground service from an exact alarm is an Android-supported
        // exemption from the normal background-FGS launch restriction.
        try{
            Intent service=new Intent(c,BellService.class);
            service.putExtra("raw",raw);
            service.putExtra("uri",uri);
            if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(service);
            else c.startService(service);
        }catch(Exception e){
            Log.e(TAG,"Gagal memulai BellService dari exact alarm",e);
        }

        // The alarm is one-shot; immediately schedule the next occurrence.
        rescheduleAll(c);
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

    static int dayBit(int calendarDay){
        return 1 << ((calendarDay + 5) % 7);
    }

    public static void rescheduleAll(Context c){
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
                        PendingIntent pi=PendingIntent.getBroadcast(
                            c,id*10+d,old,
                            PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE
                        );
                        if(pi!=null){
                            am.cancel(pi);
                            pi.cancel();
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

                PendingIntent pi=PendingIntent.getBroadcast(
                    c,requestCode,in,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE
                );

                if(android.os.Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()){
                    Log.e(TAG,"Exact alarm permission BELUM diberikan; alarm tidak dijadwalkan: "+p[2]+" "+p[1]);
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
                am.setAlarmClock(info,pi);

                Log.i(TAG,"EXACT ALARM terjadwal: "+p[2]+" "+p[1]+" -> "+best.getTime());
            }catch(SecurityException e){
                Log.e(TAG,"SecurityException saat menjadwalkan exact alarm",e);
            }catch(Exception e){
                Log.e(TAG,"Gagal menjadwalkan exact alarm",e);
            }
        }
    }
}
