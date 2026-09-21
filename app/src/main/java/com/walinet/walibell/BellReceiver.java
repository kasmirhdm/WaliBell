package com.walinet.walibell;

import android.app.*;
import android.content.*;
import java.util.*;

public class BellReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        int raw=i.getIntExtra("raw",0);
        String uri=i.getStringExtra("uri");
        try{
            Intent service=new Intent(c,BellService.class);
            service.putExtra("raw",raw);
            service.putExtra("uri",uri);
            if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(service);
            else c.startService(service);
        }catch(Exception ignored){}
        rescheduleAll(c);
    }

    static int dayBit(int calendarDay){
        return 1 << ((calendarDay + 5) % 7);
    }

    public static void rescheduleAll(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null)return;
        String data=c.getSharedPreferences("schedule",0).getString("data","");
        if(!data.isEmpty()) for(String line:data.split("\n")){
            try{
                String[] p=line.split("\\|",-1); if(p.length<6)continue;
                int id=Integer.parseInt(p[0]);
                for(int d=0;d<7;d++){
                    Intent old=new Intent(c,BellReceiver.class);
                    old.setAction("com.walinet.walibell.BELL_"+id+"_"+d);
                    PendingIntent pi=PendingIntent.getBroadcast(c,id*10+d,old,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
                    if(pi!=null){am.cancel(pi);pi.cancel();}
                }
            }catch(Exception ignored){}
        }
        if(data.isEmpty())return;

        for(String line:data.split("\n")){
            try{
                String[] p=line.split("\\|",-1); if(p.length<6)continue;
                int id=Integer.parseInt(p[0]);
                String time=p[1],uri=p[4];
                int raw=Integer.parseInt(p[5]);
                int daysMask=p.length>=7?Integer.parseInt(p[6]):31;
                if(daysMask==0)daysMask=31;
                String[] hm=time.split(":"); if(hm.length!=2)continue;
                int hour=Integer.parseInt(hm[0]),minute=Integer.parseInt(hm[1]);
                Calendar now=Calendar.getInstance(),best=null;
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
                Intent in=new Intent(c,BellReceiver.class);
                in.setAction("com.walinet.walibell.BELL_"+id+"_"+dayIndex);
                in.putExtra("raw",raw);
                in.putExtra("uri",uri);
                PendingIntent pi=PendingIntent.getBroadcast(c,id*10+dayIndex,in,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                if(android.os.Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())continue;
                if(android.os.Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,best.getTimeInMillis(),pi);
                else am.setExact(AlarmManager.RTC_WAKEUP,best.getTimeInMillis(),pi);
            }catch(SecurityException ignored){}catch(Exception ignored){}
        }
    }
}
