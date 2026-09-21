package com.walinet.walibell;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import java.util.*;

public class BellReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i){
        int raw=i.getIntExtra("raw",0); String uri=i.getStringExtra("uri");
        try{
            MediaPlayer p=raw!=0?MediaPlayer.create(c,raw):MediaPlayer.create(c,Uri.parse(uri));
            if(p!=null){p.setOnCompletionListener(MediaPlayer::release);p.start();}
            rescheduleAll(c);
        }catch(Exception ignored){}
    }

    public static void rescheduleAll(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        String data=c.getSharedPreferences("schedule",0).getString("data","");
        if(data.isEmpty()) return;
        for(String line:data.split("\n")){
            try{
                String[] p=line.split("\\|",-1); if(p.length<6)continue;
                int id=Integer.parseInt(p[0]); String time=p[1]; String uri=p[4]; int raw=Integer.parseInt(p[5]);
                String[] hm=time.split(":"); if(hm.length!=2)continue;
                Calendar cal=Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0])); cal.set(Calendar.MINUTE,Integer.parseInt(hm[1])); cal.set(Calendar.SECOND,0); cal.set(Calendar.MILLISECOND,0);
                if(cal.getTimeInMillis()<=System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR,1);
                Intent in=new Intent(c,BellReceiver.class); in.setAction("com.walinet.walibell.BELL_"+id); in.putExtra("raw",raw); in.putExtra("uri",uri);
                PendingIntent pi=PendingIntent.getBroadcast(c,id,in,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                if(android.os.Build.VERSION.SDK_INT>=23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,cal.getTimeInMillis(),pi); else am.setExact(AlarmManager.RTC_WAKEUP,cal.getTimeInMillis(),pi);
            }catch(Exception ignored){}
        }
    }
}
