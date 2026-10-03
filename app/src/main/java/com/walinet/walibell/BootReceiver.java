package com.walinet.walibell;

import android.app.AlarmManager;
import android.content.*;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG="WaliBellBoot";

    @Override public void onReceive(Context c,Intent i){
        try{
            String action=i!=null?i.getAction():"";
            if(Build.VERSION.SDK_INT>=31 &&
               "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)){
                AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
                if(am==null||!am.canScheduleExactAlarms()){
                    Log.w(TAG,"Exact alarm permission state berubah tetapi izin belum aktif.");
                    return;
                }
            }

            // Rebuild the one-shot exact alarm after reboot, app replacement,
            // time/timezone changes, or permission grant.
            BellReceiver.rescheduleAll(c);

            /*
             * DO NOT start BellService from BOOT_COMPLETED.
             * Android 15 restricts starting certain FGS types from boot.
             * The exact alarm itself is the trigger that starts BellService.
             */
        }catch(Exception e){
            Log.e(TAG,"Gagal reschedule setelah system event",e);
        }
    }
}
