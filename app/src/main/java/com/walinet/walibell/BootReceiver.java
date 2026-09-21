package com.walinet.walibell;
import android.content.*;
public class BootReceiver extends BroadcastReceiver { @Override public void onReceive(Context c,Intent i){ BellReceiver.rescheduleAll(c); } }
