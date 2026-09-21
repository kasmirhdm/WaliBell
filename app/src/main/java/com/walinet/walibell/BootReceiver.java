package com.walinet.walibell;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        try{
            BellReceiver.rescheduleAll(c);
            Intent in=new Intent(c,BellService.class);
            if(android.os.Build.VERSION.SDK_INT>=26)c.startForegroundService(in);else c.startService(in);
        }catch(Exception ignored){}
    }
}
