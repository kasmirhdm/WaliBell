package com.walinet.walibell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends ComponentActivity {
    static final int PICK_AUDIO=701, RECORD_AUDIO=702, BACKUP_CREATE=703, RESTORE_OPEN=704, PICK_SCHOOL_PHOTO=705;
    static final int PAGE_HOME=0, PAGE_SCHEDULE=1, PAGE_SOUND=2, PAGE_HISTORY=3, PAGE_DEVICE=4, PAGE_ABOUT=5;
    static final int GREEN=Color.rgb(15,118,110), DARK=Color.rgb(6,78,68);
    static final int LIGHT=Color.rgb(236,253,245), CARD=Color.rgb(248,250,249);
    LinearLayout list,content,rootLayout,bottomNav;
    TextView kioskClock,kioskCountdown;
    Handler kioskHandler=new Handler(Looper.getMainLooper());
    Runnable kioskTicker;
    Handler homeHandler=new Handler(Looper.getMainLooper());
    Runnable homeTicker;
    boolean kioskMode=false;
    ArrayList<Schedule> schedules=new ArrayList<>();
    int editingId=-1,currentPage=1;
    MediaRecorder recorder;
    String recordPath;

    // Permission flow: notification -> exact alarm -> battery optimization.
    ActivityResultLauncher<String> notificationPermissionLauncher;
    ActivityResultLauncher<Intent> exactAlarmSettingsLauncher;
    ActivityResultLauncher<Intent> schoolPhotoPickerLauncher;
    boolean protectionFlowStarted=false;
    boolean protectionIntroShown=false;
    boolean waitingForExactAlarmSettings=false;
    boolean waitingForBatterySettings=false;
    int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    String[] names={"Masuk","Pelajaran 1","Istirahat","Masuk Setelah Istirahat","Pulang"};
    String[] times={"07:00","08:00","10:10","10:30","12:30"};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);

        schoolPhotoPickerLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if(result.getResultCode()==RESULT_OK && result.getData()!=null && result.getData().getData()!=null) saveSchoolPhoto(result.getData().getData()); });

        notificationPermissionLauncher=registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                // Whether granted or denied, continue to the exact-alarm check.
                requestExactAlarmPermission();
            }
        );

        exactAlarmSettingsLauncher=registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if(Build.VERSION.SDK_INT>=31){
                    AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
                    if(am!=null && am.canScheduleExactAlarms()){
                        waitingForExactAlarmSettings=false;
                        BellReceiver.clearExactAlarmPermissionNotification(this);
                        if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);
                        requestBatteryOptimizationPermission();
                    }else{
                        waitingForExactAlarmSettings=false;
                        showExactAlarmStillMissing();
                    }
                }else{
                    requestBatteryOptimizationPermission();
                }
            }
        );

        try {
            load();
            build();
        } catch (Throwable e) {
            showStartupError(e);
            return;
        }

        // Jadwalkan alarm segera setelah UI siap.
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this); } catch (Throwable ignored) {}
            }, 300);
        } catch (Throwable ignored) {}

        // Pada Android/XOS, izin "penggunaan latar belakang" berbeda dengan
        // pengecualian optimasi baterai dan Auto-start. Jika salah satunya belum
        // aktif, alarm dapat bekerja saat aplikasi terbuka tetapi gagal saat
        // proses aplikasi tidak aktif dan layar terkunci. Tawarkan panduan sekali
        // agar pengguna dapat menyelesaikan perlindungan background.
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (!schedules.isEmpty() && needsBackgroundProtection()) {
                        showProtectionExplanation();
                    }
                } catch (Throwable ignored) {}
            }, 1200);
        } catch (Throwable ignored) {}
        
        // Jalankan foreground service saat aplikasi masih terbuka. Dengan begitu
        // WaliBell sudah berada dalam mode aktif sebelum HP dikunci, sehingga pada
        // perangkat/vendor yang ketat terhadap background-start, alarm tetap dapat
        // meneruskan perintah audio ke service yang sudah hidup.
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> { if(LicenseManager.isLicensed(this)) startBellService(); }, 500);
        } catch (Throwable ignored) {}
    }

    void showStartupError(Throwable e){
        try {
            String msg=e.getClass().getSimpleName();
            if(e.getMessage()!=null&&!e.getMessage().isEmpty())msg+="\n"+e.getMessage();
            TextView v=new TextView(this);
            v.setText("WaliBell\n\nAplikasi gagal menampilkan halaman awal.\n\n"+msg);
            v.setTextSize(16);
            v.setTextColor(Color.DKGRAY);
            v.setPadding(dp(24),dp(40),dp(24),dp(24));
            setContentView(v);
        } catch(Throwable ignored) {}
    }

    void load(){
        schedules.clear();
        String data=getSharedPreferences("schedule",0).getString("data","");
        if(!data.isEmpty()) for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length>=6)schedules.add(new Schedule(Integer.parseInt(p[0]),p[1],p[2],p[3],p[4].isEmpty()?null:p[4],Integer.parseInt(p[5]),p.length>=7?Integer.parseInt(p[6]):31));
            }catch(Exception ignored){}
        }
        if(schedules.isEmpty())for(int i=0;i<names.length;i++){ int raw=builtInRawForName(names[i]); schedules.add(new Schedule(i,times[i],names[i],names[i]+".wav",null,raw)); }
        // Bersihkan data uji coba yang terlihat pada V3.13 dan pulihkan jadwal Masuk bawaan.
        for(Schedule s:schedules){
            if("Pelajaran Pertama".equals(s.name)&&"20:13".equals(s.time)){
                s.name="Masuk"; s.time="07:00"; s.audioName="Belum ada suara"; s.audioUri=null; s.rawId=0; s.daysMask=31;
            }
        }
        for(Schedule s:schedules){ if(s.rawId==0 && (s.audioUri==null||s.audioUri.isEmpty())){ int builtIn=builtInRawForName(s.name); if(builtIn!=0){s.rawId=builtIn; s.audioName=s.name+".wav";} } }
        Collections.sort(schedules,(a,b)->{
            int ta=0,tb=0;
            try{ta=Integer.parseInt(a.time.replace(":",""));}catch(Exception ignored){}
            try{tb=Integer.parseInt(b.time.replace(":",""));}catch(Exception ignored){}
            return Integer.compare(ta,tb);
        });
        save();
    }

    boolean darkMode(){return getSharedPreferences("ui",0).getBoolean("dark",false);}
    int themeColor(){
        int i=getSharedPreferences("ui",0).getInt("theme",0);
        int[] colors={Color.rgb(15,118,110),Color.rgb(37,99,235),Color.rgb(124,58,237),Color.rgb(219,39,119),Color.rgb(234,88,12)};
        return colors[Math.max(0,Math.min(i,colors.length-1))];
    }
    int pageColor(){return darkMode()?Color.rgb(15,23,28):Color.WHITE;}
    int cardColor(){return darkMode()?Color.rgb(27,38,43):Color.rgb(248,250,249);}
    int textColor(){return darkMode()?Color.rgb(235,245,243):DARK;}
    TextView tv(String s,int z){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(textColor());v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));return v;
    }
    GradientDrawable bg(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(16));return g;}
    GradientDrawable stadiumBg(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(1000));return g;}
    Button actionButton(String text){
        Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setTextColor(textColor());b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);b.setPadding(dp(4),0,dp(4),0);b.setBackground(bg(darkMode()?Color.rgb(35,55,60):Color.rgb(231,243,240)));
        b.setMinHeight(0);b.setMinWidth(0);b.setStateListAnimator(null);return b;
    }
    Button iconButton(String text,int icon){
        Button b=actionButton(text);
        b.setCompoundDrawablesWithIntrinsicBounds(icon,0,0,0);
        b.setCompoundDrawablePadding(dp(6));
        return b;
    }
    LinearLayout navItem(String label,int icon,int page){
        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4),dp(3),dp(4),dp(3));
        ImageView iv=new ImageView(this);iv.setImageResource(icon);iv.setContentDescription(label);
        item.addView(iv,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView t=tv(label,11);t.setGravity(Gravity.CENTER);item.addView(t,new LinearLayout.LayoutParams(-1,dp(20)));
        item.setOnClickListener(v->showPage(page));return item;
    }

    void updateNavSelection(int page){
        if(bottomNav==null)return;
        int[] pages={PAGE_HOME,PAGE_SCHEDULE,PAGE_SOUND,PAGE_DEVICE,PAGE_ABOUT};
        for(int i=0;i<bottomNav.getChildCount()&&i<pages.length;i++){
            View child=bottomNav.getChildAt(i);
            boolean selected=pages[i]==page;
            child.setBackground(selected?stadiumBg(darkMode()?Color.rgb(20,83,76):Color.rgb(224,247,242)):null);
            if(child instanceof LinearLayout){
                LinearLayout item=(LinearLayout)child;
                for(int j=0;j<item.getChildCount();j++){
                    View v=item.getChildAt(j);
                    if(v instanceof ImageView){
                        ((ImageView)v).setColorFilter(selected?themeColor():(darkMode()?Color.rgb(148,163,158):Color.rgb(71,85,82)),android.graphics.PorterDuff.Mode.SRC_IN);
                    }else if(v instanceof TextView){
                        TextView t=(TextView)v;
                        t.setTextColor(selected?themeColor():(darkMode()?Color.rgb(180,200,195):Color.rgb(71,85,82)));
                        t.setTypeface(Typeface.create("sans-serif",selected?Typeface.BOLD:Typeface.NORMAL));
                    }
                }
            }
        }
    }
    TextView title(String text){
        TextView v=tv(text,23);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setTextColor(Color.WHITE);return v;
    }
    TextView subWhite(String text){TextView v=tv(text,13);v.setTextColor(Color.WHITE);return v;}

    LinearLayout header(String t,String s){
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.VERTICAL);h.setPadding(dp(16),dp(10),dp(16),dp(10));h.setBackground(bg(themeColor()));
        h.addView(title(t),new LinearLayout.LayoutParams(-1,dp(34)));h.addView(subWhite(s),new LinearLayout.LayoutParams(-1,dp(22)));return h;
    }

    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(pageColor());rootLayout=root;
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundColor(pageColor());
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);bottomNav=nav;nav.setGravity(Gravity.CENTER);nav.setPadding(dp(4),dp(4),dp(4),dp(4));
        int[] navIcons={R.drawable.ic_home,R.drawable.ic_schedule,R.drawable.ic_music,R.drawable.ic_devices,R.drawable.ic_info};
        String[] labels={"Beranda","Jadwal","Suara","Perangkat","Tentang"};
        int[] pages={PAGE_HOME,PAGE_SCHEDULE,PAGE_SOUND,PAGE_DEVICE,PAGE_ABOUT};
        for(int i=0;i<labels.length;i++)nav.addView(navItem(labels[i],navIcons[i],pages[i]),new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(nav,new LinearLayout.LayoutParams(-1,dp(62)));
        applySystemTheme();
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            root.setPadding(0,top,0,bottom);nav.setPadding(dp(4),dp(2),dp(4),dp(2));nav.getLayoutParams().height=dp(62);nav.requestLayout();return insets;
        });
        setContentView(root);root.requestApplyInsets();showPage(0);
    }

    void applySystemTheme(){
        int base=pageColor();getWindow().setStatusBarColor(base);getWindow().setNavigationBarColor(base);
        if(Build.VERSION.SDK_INT>=26){
            int flags=0;
            if(!darkMode())flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
    void clearContent(){content.removeAllViews();content.setBackgroundColor(pageColor());}
    LinearLayout card(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(bg(cardColor()));return c;
    }
    void addGap(int h){Space s=new Space(this);content.addView(s,new LinearLayout.LayoutParams(1,h));}

    void startBellService(){
        try{
            Intent in=new Intent(this,BellService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(in);else startService(in);
        }catch(Exception ignored){}
    }

    boolean needsBackgroundProtection(){
        boolean exactOk=true;
        if(Build.VERSION.SDK_INT>=31){
            AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
            exactOk=am!=null && am.canScheduleExactAlarms();
        }
        boolean batteryOk=true;
        if(Build.VERSION.SDK_INT>=23){
            android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            batteryOk=pm==null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return !exactOk || !batteryOk || !getSharedPreferences("ui",0).getBoolean("autostart_prompt",false);
    }

    void showProtectionExplanation(){
        if(protectionIntroShown)return;
        protectionIntroShown=true;

        new AlertDialog.Builder(this)
            .setTitle("WaliBell perlu izin sistem")
            .setMessage(
                "Agar bel dapat berbunyi otomatis tepat pada waktunya saat HP terkunci, " +
                "WaliBell memerlukan tiga pengaturan sistem:\\n\\n" +
                "• Notifikasi — untuk status WaliBell di latar belakang.\\n" +
                "• Alarm & Pengingat — agar jadwal bel dapat dipicu tepat waktu meskipun HP masuk Doze.\\n" +
                "• Abaikan optimasi baterai — mengurangi risiko sistem menunda atau menghentikan pekerjaan WaliBell."
            )
            .setPositiveButton("Lanjutkan",(d,w)->{
                protectionFlowStarted=true;
                requestNotificationPermission();
            })
            .setNegativeButton("Nanti",null)
            .show();
    }

    void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        requestExactAlarmPermission();
    }

    void requestExactAlarmPermission(){
        if(Build.VERSION.SDK_INT<31){
            requestBatteryOptimizationPermission();
            return;
        }

        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
        if(am!=null && !am.canScheduleExactAlarms()){
            waitingForExactAlarmSettings=true;
            new AlertDialog.Builder(this)
                .setTitle("Izinkan Alarm & Pengingat")
                .setMessage(
                    "WaliBell membutuhkan akses khusus Alarm & Pengingat agar bel dapat " +
                    "berbunyi tepat sesuai jadwal saat layar terkunci. Tekan Buka Pengaturan, " +
                    "lalu aktifkan WaliBell jika pilihan tersebut tersedia."
                )
                .setPositiveButton("OK",(d,w)->openExactAlarmSettings())
                .setNegativeButton("Nanti",null)
                .show();
            return;
        }

        waitingForExactAlarmSettings=false;
        BellReceiver.clearExactAlarmPermissionNotification(this);
        requestBatteryOptimizationPermission();
    }

    void openExactAlarmSettings(){
        boolean opened=false;
        try{
            Intent in=new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:"+getPackageName())
            );
            if(in.resolveActivity(getPackageManager())!=null){
                exactAlarmSettingsLauncher.launch(in);
                opened=true;
            }
        }catch(Exception ignored){}

        if(opened)return;

        // Some vendor Settings implementations do not resolve the package-specific
        // intent. Try the generic exact-alarm page before falling back to app info.
        try{
            Intent generic=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            if(generic.resolveActivity(getPackageManager())!=null){
                exactAlarmSettingsLauncher.launch(generic);
                opened=true;
            }
        }catch(Exception ignored){}

        if(opened)return;

        try{
            Intent appInfo=new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+getPackageName())
            );
            startActivity(appInfo);
            new AlertDialog.Builder(this)
                .setTitle("Cari akses khusus")
                .setMessage(
                    "Jika halaman Alarm & Pengingat tidak terbuka otomatis, " +
                    "buka menu Akses khusus aplikasi pada Pengaturan lalu cari " +
                    "Alarm & Pengingat dan aktifkan WaliBell."
                )
                .setPositiveButton("OK",null)
                .show();
        }catch(Exception ignored){
            try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ignored2){}
        }
    }

    void showExactAlarmStillMissing(){
        new AlertDialog.Builder(this)
            .setTitle("Izin Alarm & Pengingat belum aktif")
            .setMessage(
                "WaliBell belum mendapat akses Alarm & Pengingat. " +
                "Tanpa izin ini jadwal bel tidak dapat dijamin berbunyi tepat saat HP terkunci."
            )
            .setPositiveButton("Coba Lagi",(d,w)->openExactAlarmSettings())
            .setNegativeButton("Nanti",null)
            .show();
    }

    void requestBatteryOptimizationPermission(){
        if(Build.VERSION.SDK_INT<23){
            showAutostartGuideIfNeeded();
            return;
        }

        android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null && !pm.isIgnoringBatteryOptimizations(getPackageName())){
            waitingForBatterySettings=true;
            // Open the system battery-optimization exemption page directly.
            // This is intentionally not just an informational dialog.
            try{
                Intent in=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                in.setData(Uri.parse("package:"+getPackageName()));
                startActivity(in);
            }catch(Exception e){
                openAppSettings();
            }
            return;
        }

        waitingForBatterySettings=false;
        showAutostartGuideIfNeeded();
    }

    void openAppSettings(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}
    }

    void showAutostartGuideIfNeeded(){
        if(getSharedPreferences("ui",0).getBoolean("autostart_prompt",false))return;
        getSharedPreferences("ui",0).edit().putBoolean("autostart_prompt",true).apply();
        new AlertDialog.Builder(this).setTitle("Aktifkan Autostart")
            .setMessage("Pada Infinix/Tecno dan beberapa HP lain, aktifkan Autostart/Izinkan berjalan otomatis untuk WaliBell agar sistem tidak menghentikan aplikasi di latar belakang.")
            .setPositiveButton("Buka Pengaturan",(d,w)->openAutoStartSettings())
            .setNegativeButton("Nanti",null).show();
    }

    void openAutoStartSettings(){
        String m=Build.MANUFACTURER==null?"":Build.MANUFACTURER.toLowerCase(Locale.US);
        try{
            Intent in=null;
            if(m.contains("xiaomi")||m.contains("redmi")||m.contains("poco")){
                in=new Intent("miui.intent.action.OP_AUTO_START");in.setPackage("com.miui.securitycenter");
            }else if(m.contains("vivo")){
                in=new Intent("com.vivo.permissionmanager");in.setClassName("com.vivo.permissionmanager","com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
            }else if(m.contains("oppo")||m.contains("realme")){
                in=new Intent("com.coloros.safecenter");in.setPackage("com.coloros.safecenter");
            }else if(m.contains("huawei")||m.contains("honor")){
                in=new Intent("com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");in.setPackage("com.huawei.systemmanager");
            }else if(m.contains("infinix")||m.contains("tecno")||m.contains("itel")){
                // XOS/HiOS/itel OS use Transsion's startup manager on many
                // recent devices. If unavailable, fall back to app settings.
                in=new Intent();
                in.setComponent(new android.content.ComponentName(
                    "com.transsion.security",
                    "com.transsion.security.autostart.AutoStartActivity"
                ));
            }
            if(in!=null){startActivity(in);return;}
        }catch(Exception ignored){}
        openAppSettings();
    }

    void showExactAlarmStatus(){
        if(Build.VERSION.SDK_INT<31){
            Toast.makeText(this,"Versi Android ini tidak memerlukan akses khusus Alarm & Pengingat.",Toast.LENGTH_LONG).show();
            return;
        }
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
        boolean ok=am!=null && am.canScheduleExactAlarms();
        if(ok){
            Toast.makeText(this,"Alarm & Pengingat WaliBell sudah aktif.",Toast.LENGTH_LONG).show();
        }else{
            new AlertDialog.Builder(this)
                .setTitle("Alarm & Pengingat belum aktif")
                .setMessage("Akses khusus WaliBell belum aktif. Buka pengaturan untuk mengaktifkannya.")
                .setPositiveButton("Buka Pengaturan",(d,w)->openExactAlarmSettings())
                .setNegativeButton("Tutup",null)
                .show();
        }
    }

    @Override protected void onResume(){
        super.onResume();
        try{if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);}catch(Exception ignored){}

        // Returning from Android Settings continues the permission sequence.
        if(protectionFlowStarted){
            if(waitingForExactAlarmSettings){
                waitingForExactAlarmSettings=false;
                requestExactAlarmPermission();
            }else if(waitingForBatterySettings){
                waitingForBatterySettings=false;
                requestBatteryOptimizationPermission();
            }
        }
    }

    void showPage(int page){
        if(homeTicker!=null)homeHandler.removeCallbacks(homeTicker);
        currentPage=page;
        kioskMode=false;
        if(bottomNav!=null)bottomNav.setVisibility(View.VISIBLE);
        applySystemTheme();
        updateNavSelection(page);
        if(page==PAGE_HOME)showHome();
        else if(page==PAGE_SCHEDULE)showSchedulePage();
        else if(page==PAGE_SOUND)showSoundPage();
        else if(page==PAGE_HISTORY)showHistoryPage();
        else if(page==PAGE_DEVICE)showDevicePage();
        else showAboutPage();
    }

    String schoolName(){ return getSharedPreferences("school_identity",0).getString("school_name","SMPN 1 Sungai Batang"); }
    String schoolLocation(){ return getSharedPreferences("school_identity",0).getString("school_location","Indragiri Hilir - Riau"); }
    android.graphics.Bitmap schoolPhotoBitmap(){
        try{ String path=getSharedPreferences("school_identity",0).getString("school_photo_path","");
            if(!path.isEmpty()){ android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeFile(path); if(b!=null)return b; }
            return android.graphics.BitmapFactory.decodeResource(getResources(),R.drawable.school_photo);
        }catch(Exception e){return null;}
    }
    void saveSchoolPhoto(Uri uri){
        try{ File out=new File(getFilesDir(),"school_photo.jpg");
            try(InputStream in=getContentResolver().openInputStream(uri); OutputStream os=new FileOutputStream(out)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)os.write(buf,0,n);}
            getSharedPreferences("school_identity",0).edit().putString("school_photo_path",out.getAbsolutePath()).apply();
            Toast.makeText(this,"Foto sekolah berhasil disimpan",Toast.LENGTH_SHORT).show(); showHome();
        }catch(Exception e){Toast.makeText(this,"Gagal menyimpan foto sekolah",Toast.LENGTH_SHORT).show();}
    }
    void chooseSchoolPhoto(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");schoolPhotoPickerLauncher.launch(i);}
    void showSchoolIdentityDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),0,dp(8),0);
        EditText name=new EditText(this);name.setHint("Nama sekolah");name.setText(schoolName());box.addView(name,new LinearLayout.LayoutParams(-1,dp(60)));
        EditText loc=new EditText(this);loc.setHint("Kabupaten/Kota - Provinsi");loc.setText(schoolLocation());box.addView(loc,new LinearLayout.LayoutParams(-1,dp(60)));
        Button photo=actionButton("Pilih / Ganti Foto Sekolah");photo.setOnClickListener(v->chooseSchoolPhoto());box.addView(photo,new LinearLayout.LayoutParams(-1,dp(48)));
        new AlertDialog.Builder(this).setTitle("Identitas Sekolah").setMessage("Nama, lokasi, dan foto dapat diubah kapan saja.").setView(box).setNegativeButton("Batal",null).setPositiveButton("Simpan",(d,w)->{
            String n=name.getText().toString().trim(),l=loc.getText().toString().trim();if(n.isEmpty())n="Nama Sekolah";if(l.isEmpty())l="Kabupaten/Kota - Provinsi";
            getSharedPreferences("school_identity",0).edit().putString("school_name",n).putString("school_location",l).putBoolean("school_setup_done",true).apply();showHome();
        }).show();
    }
    void showFirstSchoolSetup(){new AlertDialog.Builder(this).setTitle("Selamat Datang di WaliBell").setMessage("Silakan isi identitas sekolah. Foto sekolah dapat diganti kapan saja melalui menu Tentang.").setPositiveButton("Atur Sekarang",(d,w)->showSchoolIdentityDialog()).setCancelable(false).show();}

    void showHome(){
        clearContent();

        // Beranda dibuat compact agar seluruh informasi utama muat dalam satu
        // layar tanpa scroll pada layar HP sekolah yang relatif kecil.
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(6),dp(4),dp(6),dp(4));

        // ===== SCHOOL PHOTO HERO =====
        FrameLayout hero=new FrameLayout(this);hero.setClipToOutline(true);
        GradientDrawable heroShape=new GradientDrawable();heroShape.setCornerRadius(dp(18));heroShape.setColor(Color.rgb(8,47,73));hero.setBackground(heroShape);hero.setElevation(dp(2));
        ImageView heroPhoto=new ImageView(this);heroPhoto.setImageBitmap(schoolPhotoBitmap());heroPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);hero.addView(heroPhoto,new FrameLayout.LayoutParams(-1,-1));
        View darkOverlay=new View(this);darkOverlay.setBackgroundColor(Color.argb(125,0,25,45));hero.addView(darkOverlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heroContent=new LinearLayout(this);heroContent.setOrientation(LinearLayout.HORIZONTAL);heroContent.setGravity(Gravity.CENTER_VERTICAL);heroContent.setPadding(dp(14),dp(3),dp(12),dp(3));
        ImageView bellIcon=new ImageView(this);bellIcon.setImageResource(R.drawable.ic_bell);bellIcon.setColorFilter(Color.rgb(250,204,21),android.graphics.PorterDuff.Mode.SRC_IN);heroContent.addView(bellIcon,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout heroText=new LinearLayout(this);heroText.setOrientation(LinearLayout.VERTICAL);heroText.setPadding(dp(8),0,0,0);
        TextView h1=new TextView(this);h1.setText("WaliBell");h1.setTextColor(Color.WHITE);h1.setTextSize(22);h1.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView h2=new TextView(this);h2.setText("Bel Sekolah Otomatis");h2.setTextColor(Color.WHITE);h2.setTextSize(12);
        TextView school=new TextView(this);school.setText(schoolName().toUpperCase(Locale.ROOT));school.setTextColor(Color.rgb(250,204,21));school.setTextSize(10);school.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heroText.addView(h1,new LinearLayout.LayoutParams(-1,dp(27)));heroText.addView(h2,new LinearLayout.LayoutParams(-1,dp(19)));heroText.addView(school,new LinearLayout.LayoutParams(-1,dp(17)));
        heroContent.addView(heroText,new LinearLayout.LayoutParams(0,-2,1));hero.addView(heroContent,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        page.addView(hero,new LinearLayout.LayoutParams(-1,dp(92)));

        // ===== CLOCK & STATUS =====
        LinearLayout statusCard=card();
        statusCard.setPadding(dp(12),dp(7),dp(12),dp(7));
        statusCard.setElevation(dp(2));
        LinearLayout cols=new LinearLayout(this);cols.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);
        TextView date=tv("",11);date.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView clock=tv("",28);clock.setTypeface(Typeface.DEFAULT,Typeface.BOLD);clock.setTextColor(Color.rgb(7,55,90));
        left.addView(date,new LinearLayout.LayoutParams(-1,dp(21)));
        left.addView(clock,new LinearLayout.LayoutParams(-1,dp(34)));
        cols.addView(left,new LinearLayout.LayoutParams(0,dp(62),1.15f));

        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(5),0,0,0);
        LinearLayout activeRow=new LinearLayout(this);activeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView activeText=tv("",12);activeText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);activeText.setTextColor(Color.rgb(22,101,52));
        Switch bellSwitch=new Switch(this);bellSwitch.setChecked(getSharedPreferences("ui",0).getBoolean("bell_enabled",true));
        activeRow.addView(activeText,new LinearLayout.LayoutParams(0,dp(24),1));
        activeRow.addView(bellSwitch,new LinearLayout.LayoutParams(dp(48),dp(30)));
        right.addView(activeRow);
        LinearLayout btRow=new LinearLayout(this);btRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView btIcon=tv("ᛒ",13);btIcon.setTextColor(Color.rgb(100,116,139));
        TextView btText=tv("",10);btRow.addView(btIcon,new LinearLayout.LayoutParams(dp(18),dp(20)));btRow.addView(btText,new LinearLayout.LayoutParams(0,dp(20),1));
        right.addView(btRow);
        TextView activeStatus=tv("",10);activeStatus.setTypeface(Typeface.DEFAULT,Typeface.BOLD);activeStatus.setTextColor(Color.rgb(22,163,74));
        right.addView(activeStatus,new LinearLayout.LayoutParams(-1,dp(17)));
        cols.addView(right,new LinearLayout.LayoutParams(0,dp(62),1));
        statusCard.addView(cols);
        LinearLayout.LayoutParams scp=new LinearLayout.LayoutParams(-1,dp(78));scp.setMargins(0,dp(4),0,0);page.addView(statusCard,scp);

        // ===== NEXT SCHEDULE =====
        LinearLayout nextCard=new LinearLayout(this);nextCard.setOrientation(LinearLayout.HORIZONTAL);nextCard.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.setPadding(dp(10),dp(3),dp(10),dp(3));nextCard.setBackground(bg(Color.rgb(224,242,254)));nextCard.setElevation(dp(1));
        ImageView alarmIcon=new ImageView(this);alarmIcon.setImageResource(R.drawable.ic_schedule);alarmIcon.setColorFilter(Color.rgb(220,38,38),android.graphics.PorterDuff.Mode.SRC_IN);
        nextCard.addView(alarmIcon,new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView nextText=tv("",11);nextText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);nextText.setTextColor(Color.rgb(15,55,80));
        nextCard.addView(nextText,new LinearLayout.LayoutParams(0,dp(34),1));
        LinearLayout.LayoutParams ncp=new LinearLayout.LayoutParams(-1,dp(52));ncp.setMargins(0,dp(4),0,0);page.addView(nextCard,ncp);

        // ===== MANUAL BELL =====
        LinearLayout manualTitle=new LinearLayout(this);manualTitle.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mi=new ImageView(this);mi.setImageResource(R.drawable.ic_bell);mi.setColorFilter(Color.rgb(7,55,90),android.graphics.PorterDuff.Mode.SRC_IN);
        manualTitle.addView(mi,new LinearLayout.LayoutParams(dp(19),dp(19)));
        TextView mt=tv("Bel Manual",14);mt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);manualTitle.addView(mt,new LinearLayout.LayoutParams(-1,dp(22)));
        LinearLayout.LayoutParams mtp=new LinearLayout.LayoutParams(-1,dp(28));mtp.setMargins(0,dp(4),0,0);page.addView(manualTitle,mtp);

        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);
        String[] manualNames={"BEL MASUK","PERGANTIAN","ISTIRAHAT","MASUK SETELAH ISTIRAHAT","BEL PULANG","BUNYIKAN SEKARANG"};
        int[] manualColors={Color.rgb(219,239,255),Color.rgb(255,239,204),Color.rgb(220,247,226),Color.rgb(237,225,255),Color.rgb(255,225,232),Color.rgb(7,55,90)};
        int[] manualIcons={R.drawable.ic_bell,R.drawable.ic_schedule,R.drawable.ic_music,R.drawable.ic_devices,R.drawable.ic_home,R.drawable.ic_music};
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
            for(int col=0;col<3;col++){
                final int index=row*3+col;
                Button mb=actionButton(manualNames[index]);mb.setTextSize(index==3?8:9);mb.setTypeface(Typeface.DEFAULT,Typeface.BOLD);mb.setGravity(Gravity.CENTER);
                mb.setCompoundDrawablesWithIntrinsicBounds(manualIcons[index],0,0,0);mb.setCompoundDrawablePadding(dp(2));mb.setPadding(dp(1),0,dp(1),0);
                mb.setBackground(bg(manualColors[index]));mb.setTextColor(index==5?Color.WHITE:Color.rgb(15,55,80));if(index==5)mb.setBackground(stadiumBg(Color.rgb(7,55,90)));
                mb.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);playManualBell(index);});
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(51),1);bp.setMargins(dp(2),dp(1),dp(2),dp(1));line.addView(mb,bp);
            }
            grid.addView(line,new LinearLayout.LayoutParams(-1,dp(56)));
        }
        page.addView(grid,new LinearLayout.LayoutParams(-1,dp(116)));

        // ===== TODAY SCHEDULE: 2 kolom agar enam jadwal tetap terlihat =====
        LinearLayout todayTitle=new LinearLayout(this);todayTitle.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ci=new ImageView(this);ci.setImageResource(R.drawable.ic_schedule);ci.setColorFilter(Color.rgb(7,55,90),android.graphics.PorterDuff.Mode.SRC_IN);
        todayTitle.addView(ci,new LinearLayout.LayoutParams(dp(19),dp(19)));
        LinearLayout todayWords=new LinearLayout(this);todayWords.setOrientation(LinearLayout.VERTICAL);
        TextView tt=tv("Jadwal Hari Ini",14);tt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView count=tv("",9);todayWords.addView(tt,new LinearLayout.LayoutParams(-1,dp(17)));todayWords.addView(count,new LinearLayout.LayoutParams(-1,dp(13)));
        todayTitle.addView(todayWords,new LinearLayout.LayoutParams(0,dp(30),1));
        LinearLayout.LayoutParams ttp=new LinearLayout.LayoutParams(-1,dp(30));ttp.setMargins(0,dp(4),0,0);page.addView(todayTitle,ttp);

        LinearLayout scheduleGrid=new LinearLayout(this);scheduleGrid.setOrientation(LinearLayout.VERTICAL);
        page.addView(scheduleGrid,new LinearLayout.LayoutParams(-1,0,1));

        // Tampilkan halaman compact memenuhi area konten di atas bottom navigation.
        content.addView(page,new LinearLayout.LayoutParams(-1,0,1));

        // Pastikan isi hari ini dapat dirender tanpa ScrollView.
        Runnable refresh=()->{
            Calendar now=Calendar.getInstance();
            date.setText(new SimpleDateFormat("EEEE, dd MMMM yyyy",new Locale("id","ID")).format(now.getTime()));
            clock.setText(new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(now.getTime()));

            Schedule next=getNextSchedule();
            if(next==null) nextText.setText("Berikutnya: Tidak ada jadwal");
            else{
                Calendar target=nextOccurrence(next);
                long diff=Math.max(0,target.getTimeInMillis()-now.getTimeInMillis())/60000;
                String remain=diff<1?"< 1 menit":(diff+" menit lagi");
                nextText.setText("Berikutnya: "+next.name+" • "+next.time+"  |  "+remain);
            }

            boolean connected=isBluetoothConnected();
            btText.setText(connected?"Bluetooth terhubung":"Bluetooth belum terhubung");
            btText.setTextColor(connected?Color.rgb(22,163,74):Color.rgb(100,116,139));
            btIcon.setTextColor(connected?Color.rgb(22,163,74):Color.rgb(100,116,139));
            activeText.setText(bellSwitch.isChecked()?"Bel Aktif":"Bel Nonaktif");
            activeStatus.setText(bellSwitch.isChecked()?"✓ Sistem berjalan":"Bel otomatis tidak aktif");
            activeStatus.setTextColor(bellSwitch.isChecked()?Color.rgb(22,163,74):Color.rgb(148,163,184));

            scheduleGrid.removeAllViews();
            Calendar today=Calendar.getInstance();
            int todayBit=1<<((today.get(Calendar.DAY_OF_WEEK)+5)%7);
            ArrayList<Schedule> todaySchedules=new ArrayList<>();
            for(Schedule s:schedules)if((s.daysMask&todayBit)!=0)todaySchedules.add(s);
            Collections.sort(todaySchedules,(x,y)->x.time.compareTo(y.time));
            count.setText(todaySchedules.size()+" waktu");

            for(int row=0;row<(todaySchedules.size()+1)/2;row++){
                LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
                for(int col=0;col<2;col++){
                    int idx=row*2+col;
                    if(idx<todaySchedules.size()){
                        Schedule s=todaySchedules.get(idx);
                        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER_VERTICAL);
                        item.setPadding(dp(7),dp(2),dp(7),dp(2));
                        boolean done=isScheduleTimePassed(s.time);
                        Schedule nextForColor=getNextSchedule();
                        boolean isNext=nextForColor!=null&&s.time.equals(nextForColor.time)&&s.name.equals(nextForColor.name);
                        int scheduleBg;
                        if(done) scheduleBg=darkMode()?Color.rgb(31,58,45):Color.rgb(235,248,240);
                        else if(isNext) scheduleBg=darkMode()?Color.rgb(67,58,25):Color.rgb(255,249,224);
                        else scheduleBg=darkMode()?Color.rgb(62,36,40):Color.rgb(255,239,242);
                        item.setBackground(bg(scheduleBg));item.setElevation(dp(1));
                        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
                        TextView tm=tv(s.time,12);tm.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                        top.addView(tm,new LinearLayout.LayoutParams(dp(39),dp(17)));
                        TextView stt=tv(done?"✓":"◷",10);stt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);stt.setGravity(Gravity.CENTER);
                        stt.setTextColor(done?Color.rgb(22,163,74):Color.rgb(220,38,38));
                        top.addView(stt,new LinearLayout.LayoutParams(dp(18),dp(17)));
                        item.addView(top);
                        TextView nm=tv(s.name,11);nm.setSingleLine(true);nm.setEllipsize(android.text.TextUtils.TruncateAt.END);item.addView(nm,new LinearLayout.LayoutParams(-1,dp(15)));
                        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,dp(72),1);ip.setMargins(dp(2),dp(1),dp(2),dp(1));line.addView(item,ip);
                    }else{
                        Space empty=new Space(this);line.addView(empty,new LinearLayout.LayoutParams(0,dp(72),1));
                    }
                }
                scheduleGrid.addView(line,new LinearLayout.LayoutParams(-1,dp(76)));
            }
        };

        bellSwitch.setOnCheckedChangeListener((buttonView,isChecked)->{
            getSharedPreferences("ui",0).edit().putBoolean("bell_enabled",isChecked).apply();
            if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);
            activeText.setText(isChecked?"Bel Aktif":"Bel Nonaktif");
            activeStatus.setText(isChecked?"✓ Sistem berjalan":"Bel otomatis tidak aktif");
        });
        homeTicker=()->{
            if(currentPage==PAGE_HOME){refresh.run();homeHandler.postDelayed(homeTicker,1000);}
        };
        refresh.run();
        homeHandler.postDelayed(homeTicker,1000);
    }

    boolean isBluetoothConnected(){
        try{
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return false;
            BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
            if(a==null||!a.isEnabled())return false;
            return a.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)==BluetoothAdapter.STATE_CONNECTED
                || a.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)==BluetoothAdapter.STATE_CONNECTED;
        }catch(Exception e){return false;}
    }

    boolean isScheduleTimePassed(String time){
        try{
            Calendar n=Calendar.getInstance();
            String[] hm=time.split(":");
            Calendar t=(Calendar)n.clone();
            t.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));
            t.set(Calendar.MINUTE,Integer.parseInt(hm[1]));
            t.set(Calendar.SECOND,59);
            t.set(Calendar.MILLISECOND,999);
            return n.after(t);
        }catch(Exception e){return false;}
    }

    Schedule findManualSchedule(String... keys){
        for(String key:keys){
            for(Schedule s:schedules){
                String n=s.name.toLowerCase(Locale.US);
                if(n.contains(key.toLowerCase(Locale.US)) &&
                   (s.rawId!=0 || (s.audioUri!=null&&!s.audioUri.isEmpty())))return s;
            }
        }
        return null;
    }

    void playManualBell(int index){
        String[] keys;
        switch(index){
            case 0:keys=new String[]{"masuk"};break;
            case 1:keys=new String[]{"pergantian","pelajaran"};break;
            case 2:keys=new String[]{"istirahat"};break;
            case 3:keys=new String[]{"masuk setelah istirahat"};break;
            case 4:keys=new String[]{"pulang"};break;
            default:keys=new String[]{""};
        }
        Schedule s=findManualSchedule(keys);
        if(s==null && index==5){
            for(Schedule x:schedules)if(x.rawId!=0||(x.audioUri!=null&&!x.audioUri.isEmpty())){s=x;break;}
        }
        if(s==null){
            Toast.makeText(this,"Belum ada suara untuk tombol ini. Atur suara di menu Suara.",Toast.LENGTH_LONG).show();
            return;
        }
        preview(s);
    }

    void showSchedulePage(){
        clearContent();content.addView(header("Jadwal Bel","Atur waktu dan suara untuk setiap bel"),new LinearLayout.LayoutParams(-1,dp(78)));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(list);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        Button add=iconButton("Tambah Jadwal",R.drawable.ic_add);add.setTextColor(Color.WHITE);add.setTextSize(14);add.setBackground(stadiumBg(themeColor()));add.setOnClickListener(v->addSchedule());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(dp(8),dp(6),dp(8),dp(8));content.addView(add,ap);render();
    }

    void setupTimeInput(EditText time){
        time.setSingleLine(true);
        time.setFocusable(false);
        time.setClickable(true);
        time.setHint("Pilih waktu, misalnya 13:00");
        time.setOnClickListener(v->{
            int hour=7, minute=0;
            String current=time.getText().toString().trim();
            try{
                String[] hm=current.split(":");
                if(hm.length==2){
                    hour=Integer.parseInt(hm[0]);
                    minute=Integer.parseInt(hm[1]);
                }
            }catch(Exception ignored){}
            TimePickerDialog picker=new TimePickerDialog(this,(view,h,m)->{
                time.setText(String.format(Locale.getDefault(),"%02d:%02d",h,m));
            },hour,minute,true);
            picker.setTitle("Pilih waktu bel");
            picker.show();
        });
    }

    void addSchedule(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(4),dp(4),dp(4),0);
        EditText name=new EditText(this);name.setHint("Nama bel, misalnya Upacara");form.addView(name,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText time=new EditText(this);time.setHint("Waktu (HH:mm), ketik 1300 untuk 13:00");setupTimeInput(time);form.addView(time,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView dh=tv("Hari bel berbunyi",14);dh.setTypeface(Typeface.DEFAULT,Typeface.BOLD);form.addView(dh,new LinearLayout.LayoutParams(-1,dp(30)));
        dayChecks=addDayChecks(form,31);
        new AlertDialog.Builder(this).setTitle("Tambah Jadwal Bel").setView(form).setPositiveButton("Simpan",(d,w)->{
            String n=name.getText().toString().trim(),t=time.getText().toString().trim();
            if(n.isEmpty())n="Jadwal Baru";
            if(!t.matches("([01]\\d|2[0-3]):[0-5]\\d")){Toast.makeText(this,"Format waktu harus HH:mm, contoh 13:00",Toast.LENGTH_LONG).show();return;}
            int id=0;for(Schedule s:schedules)id=Math.max(id,s.id+1);
            schedules.add(new Schedule(id,t,n,"Belum ada suara",null,0,getDaysMask(dayChecks)));
            save();if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    void showSoundPage(){
        clearContent();content.addView(header("Kelola Suara","Kelola suara yang digunakan setiap jadwal"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);if(schedules.isEmpty()){box.addView(emptyState("♫","Belum ada suara","Tambahkan jadwal terlebih dahulu."));}
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=tv("♫  "+s.name+"\n     "+s.audioName,14);row.addView(name,new LinearLayout.LayoutParams(0,dp(58),1));
            Button play=iconButton("",R.drawable.ic_play);play.setOnClickListener(v->preview(s));row.addView(play,new LinearLayout.LayoutParams(dp(50),dp(42)));
            Button b=actionButton("Pilih");b.setOnClickListener(v->chooseAudio(s.id));row.addView(b,new LinearLayout.LayoutParams(dp(70),dp(42)));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(70));rp.setMargins(dp(8),dp(4),dp(8),0);box.addView(row,rp);
        }
        Button add=iconButton("Tambah Suara Baru",R.drawable.ic_add);add.setTextColor(Color.WHITE);add.setBackground(stadiumBg(Color.rgb(13,148,136)));add.setOnClickListener(v->{if(!schedules.isEmpty())chooseAudio(schedules.get(0).id);});
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(dp(8),dp(8),dp(8),dp(8));box.addView(add,ap);
        ScrollView sv=new ScrollView(this);sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showDevicePage(){
        clearContent();content.addView(header("Perangkat","Pengaturan keluaran suara"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout out=card();TextView ot=tv("Output Suara",17);ot.setTypeface(Typeface.DEFAULT,Typeface.BOLD);ot.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_volume,0,0,0);ot.setCompoundDrawablePadding(8);out.addView(ot,new LinearLayout.LayoutParams(-1,dp(32)));
        RadioGroup rg=new RadioGroup(this);
        String[] opts={"Speaker HP (bawaan)","Bluetooth Speaker","Perangkat USB / OTG"};
        for(String x:opts){
            RadioButton rb=new RadioButton(this);rb.setText(x);rb.setTextSize(15);rb.setTextColor(DARK);
            rg.addView(rb,new RadioGroup.LayoutParams(-1,dp(38)));
            if(x.startsWith("Speaker"))rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((group,checkedId)->{
            RadioButton selected=group.findViewById(checkedId);
            if(selected!=null&&selected.getText().toString().startsWith("Bluetooth"))ensureBluetoothReady();
        });
        out.addView(rg,new LinearLayout.LayoutParams(-1,dp(120)));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(180));op.setMargins(dp(8),dp(8),dp(8),0);content.addView(out,op);

        LinearLayout vol=card();vol.addView(tv("Volume Bel",17),new LinearLayout.LayoutParams(-1,dp(30)));
        SeekBar sb=new SeekBar(this);sb.setProgress(80);vol.addView(sb,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,dp(82));vp.setMargins(dp(8),dp(6),dp(8),0);content.addView(vol,vp);

        LinearLayout note=card();TextView nt=tv("Notifikasi\nTampilkan notifikasi saat bel diputar",15);nt.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bell,0,0,0);nt.setCompoundDrawablePadding(8);note.addView(nt,new LinearLayout.LayoutParams(-1,dp(58)));
        Switch sw=new Switch(this);sw.setChecked(true);sw.setText("Aktif");note.addView(sw,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(112));np.setMargins(dp(8),dp(6),dp(8),0);content.addView(note,np);
    }

    void ensureBluetoothReady(){
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null){
            new AlertDialog.Builder(this).setTitle("Bluetooth tidak tersedia").setMessage("Perangkat ini tidak mendukung Bluetooth.").setPositiveButton("OK",null).show();
            return;
        }
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},910);
            return;
        }
        if(!adapter.isEnabled()){
            new AlertDialog.Builder(this).setTitle("Bluetooth belum aktif")
                .setMessage("Aktifkan Bluetooth terlebih dahulu agar WaliBell dapat menggunakan speaker Bluetooth.")
                .setPositiveButton("Buka Pengaturan", (d,w)->{
                    try{startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
                }).setNegativeButton("Batal",null).show();
        }else{
            Toast.makeText(this,"Bluetooth aktif",Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==911){showAutostartGuideIfNeeded();return;}
        if(requestCode==910){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)ensureBluetoothReady();
            else Toast.makeText(this,"Izin Bluetooth diperlukan untuk mengecek status Bluetooth.",Toast.LENGTH_LONG).show();
        }
    }

    Button toolInfoButton(String title,String description,Runnable action){
        Button b=actionButton(title+"\n"+description);
        b.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        b.setPadding(dp(16),dp(6),dp(12),dp(6));
        b.setTextSize(13);
        b.setOnClickListener(v->action.run());
        return b;
    }

    void showAboutPage(){
        clearContent();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(0,0,0,dp(56));
        page.addView(header("Tentang","Informasi aplikasi"),new LinearLayout.LayoutParams(-1,dp(78)));

        // Identitas aplikasi dibuat ringkas agar proporsional, sementara seluruh
        // informasi dan alat tetap mudah dibaca dalam halaman yang dapat di-scroll.
        LinearLayout box=card();box.setGravity(Gravity.CENTER);box.setPadding(dp(14),dp(8),dp(14),dp(8));
        TextView n=tv("WaliBell",28);n.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));n.setGravity(Gravity.CENTER);n.setTextColor(themeColor());
        box.addView(n,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView v=tv("Versi 4.5\nSistem Bel Sekolah Otomatis\nProfesional, praktis, dan mudah dikelola\n\n"+schoolName()+"\n"+schoolLocation()+"\n\nPengelola Sistem\nKasmir, S.Pd\nOperator Sekolah",15);
        v.setGravity(Gravity.CENTER);box.addView(v,new LinearLayout.LayoutParams(-1,dp(198)));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(260));bp.setMargins(dp(8),dp(8),dp(8),0);page.addView(box,bp);

        TextView quote=tv("“Disiplin dimulai dari waktu”",18);quote.setTextColor(themeColor());quote.setGravity(Gravity.CENTER);
        page.addView(quote,new LinearLayout.LayoutParams(-1,dp(48)));

        LinearLayout tools=card();tools.setPadding(dp(10),dp(8),dp(10),dp(8));
        TextView th=tv("Tampilan & Alat",17);th.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        tools.addView(th,new LinearLayout.LayoutParams(-1,dp(30)));

        Button identityBtn=toolInfoButton("Identitas Sekolah","Atur nama, lokasi, dan foto sekolah.",()->showSchoolIdentityDialog());
        tools.addView(identityBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button themeBtn=toolInfoButton("Mode Gelap / Terang","Ubah tampilan sesuai kenyamanan pengguna.",()->toggleDarkMode());
        tools.addView(themeBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button colorBtn=toolInfoButton("Warna Tema","Pilih warna utama tampilan WaliBell.",()->chooseTheme());
        tools.addView(colorBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button kioskBtn=toolInfoButton("Mode Layar Penuh / Kiosk","Gunakan HP sebagai perangkat bel khusus.",()->showKioskPage());
        tools.addView(kioskBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button backupBtn=toolInfoButton("Backup Data Jadwal & Suara","Simpan jadwal, hari, dan suara ke satu file ZIP.",()->backupData());
        tools.addView(backupBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button restoreBtn=toolInfoButton("Restore Data","Kembalikan jadwal dan suara dari file backup.",()->restoreData());
        tools.addView(restoreBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        Button alarmBtn=toolInfoButton("Cek Alarm & Pengingat","Pastikan izin alarm agar bel otomatis bekerja tepat waktu.",()->showExactAlarmStatus());
        tools.addView(alarmBtn,new LinearLayout.LayoutParams(-1,dp(58)));

        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(dp(8),dp(8),dp(8),dp(8));page.addView(tools,tp);

        TextView tip=tv("Setiap menu di atas memiliki penjelasan singkat agar operator sekolah mudah memahami fungsi dan pengaturannya.",13);
        tip.setPadding(dp(12),dp(8),dp(12),dp(12));page.addView(tip,new LinearLayout.LayoutParams(-1,dp(54)));

        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(page);content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showHistoryPage(){
        clearContent();content.addView(header("Riwayat Bel","Catatan waktu bel berbunyi"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout box=card();
        String data=getSharedPreferences("history",0).getString("data","");
        if(data.isEmpty()){
            box.addView(emptyState("◷","Belum ada riwayat","Riwayat akan muncul otomatis setiap bel berbunyi."),new LinearLayout.LayoutParams(-1,dp(190)));
        }else{
            String[] lines=data.split("\\n");
            for(int i=lines.length-1;i>=0;i--){if(lines[i].trim().isEmpty())continue;TextView row=tv("•  "+lines[i],14);box.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));}
        }
        ScrollView sv=new ScrollView(this);sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        Button clear=actionButton("Hapus Riwayat");clear.setTextColor(Color.WHITE);clear.setBackground(stadiumBg(themeColor()));clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Hapus Riwayat").setMessage("Semua catatan riwayat akan dihapus.").setPositiveButton("Hapus",(d,w)->{getSharedPreferences("history",0).edit().clear().apply();showHistoryPage();}).setNegativeButton("Batal",null).show());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(48));cp.setMargins(dp(8),dp(6),dp(8),dp(8));content.addView(clear,cp);
    }

    void toggleDarkMode(){
        getSharedPreferences("ui",0).edit().putBoolean("dark",!darkMode()).apply();
        build();
    }
    void chooseTheme(){
        String[] names={"Teal Premium","Biru Royal","Ungu Elegan","Pink Modern","Oranye Hangat"};
        new AlertDialog.Builder(this).setTitle("Warna Tema").setSingleChoiceItems(names,getSharedPreferences("ui",0).getInt("theme",0),(d,w)->{getSharedPreferences("ui",0).edit().putInt("theme",w).apply();d.dismiss();build();}).show();
    }

    void showKioskPage(){
        kioskMode=true;
        if(bottomNav!=null)bottomNav.setVisibility(View.GONE);
        clearContent();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        content.setBackgroundColor(pageColor());
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(24),dp(30),dp(24),dp(30));
        TextView school=tv("SMPN 1 SUNGAI BATANG",18);school.setGravity(Gravity.CENTER);school.setTextColor(themeColor());school.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));box.addView(school,new LinearLayout.LayoutParams(-1,dp(40)));
        kioskClock=tv("00:00:00",64);kioskClock.setGravity(Gravity.CENTER);kioskClock.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));box.addView(kioskClock,new LinearLayout.LayoutParams(-1,dp(100)));
        TextView nextLabel=tv("BEL BERIKUTNYA",14);nextLabel.setGravity(Gravity.CENTER);nextLabel.setTextColor(themeColor());box.addView(nextLabel,new LinearLayout.LayoutParams(-1,dp(30)));
        kioskCountdown=tv("--:--:--",42);kioskCountdown.setGravity(Gravity.CENTER);kioskCountdown.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));box.addView(kioskCountdown,new LinearLayout.LayoutParams(-1,dp(75)));
        Button exit=iconButton("Keluar Mode Layar Penuh",R.drawable.ic_home);exit.setTextColor(Color.WHITE);exit.setBackground(stadiumBg(themeColor()));exit.setOnClickListener(v->exitKiosk());box.addView(exit,new LinearLayout.LayoutParams(-1,dp(54)));
        content.addView(box,new LinearLayout.LayoutParams(-1,-1));
        kioskTicker=new Runnable(){public void run(){updateKiosk();if(kioskMode)kioskHandler.postDelayed(this,1000);}};
        kioskHandler.post(kioskTicker);
    }
    void updateKiosk(){
        if(kioskClock==null)return;
        kioskClock.setText(new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
        Schedule next=getNextSchedule();
        if(next==null){kioskCountdown.setText("--:--:--");return;}
        Calendar target=nextOccurrence(next),now=Calendar.getInstance();
        long diff=Math.max(0,target.getTimeInMillis()-now.getTimeInMillis())/1000;
        long h=diff/3600,m=(diff%3600)/60,s=diff%60;
        kioskCountdown.setText(next.name+"\n"+String.format(Locale.getDefault(),"%02d:%02d:%02d",h,m,s));
    }
    Calendar nextOccurrence(Schedule s){
        Calendar now=Calendar.getInstance();
        for(int off=0;off<7;off++){Calendar cal=(Calendar)now.clone();cal.add(Calendar.DAY_OF_YEAR,off);int bit=1<<((cal.get(Calendar.DAY_OF_WEEK)+5)%7);if((s.daysMask&bit)==0)continue;String[] hm=s.time.split(":");cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));cal.set(Calendar.MINUTE,Integer.parseInt(hm[1]));cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);if(cal.after(now))return cal;}
        return now;
    }
    void exitKiosk(){
        kioskMode=false;if(kioskTicker!=null)kioskHandler.removeCallbacks(kioskTicker);
        getWindow().getDecorView().setSystemUiVisibility(0);applySystemTheme();showPage(PAGE_HOME);
    }

    void backupData(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"WaliBell_Backup.zip");startActivityForResult(i,BACKUP_CREATE);
    }
    void restoreData(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,RESTORE_OPEN);
    }
    void backupToUri(Uri uri){
        try{
            OutputStream os=getContentResolver().openOutputStream(uri);ZipOutputStream zos=new ZipOutputStream(os);
            ZipEntry se=new ZipEntry("schedule.txt");zos.putNextEntry(se);
            for(Schedule s:schedules){
                String entry="";
                if(s.audioUri!=null&&!s.audioUri.isEmpty()){entry="audio/"+s.id+".audio";}
                String line=s.id+"|"+s.time+"|"+s.name+"|"+s.audioName+"|"+entry+"|"+s.rawId+"|"+s.daysMask+"\n";zos.write(line.getBytes("UTF-8"));
            }
            zos.closeEntry();
            for(Schedule s:schedules){
                if(s.audioUri==null||s.audioUri.isEmpty())continue;
                InputStream in=null;
                try{if(s.audioUri.startsWith("content:"))in=getContentResolver().openInputStream(Uri.parse(s.audioUri));else in=new FileInputStream(new File(Uri.parse(s.audioUri).getPath()));if(in==null)continue;
                    zos.putNextEntry(new ZipEntry("audio/"+s.id+".audio"));byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)zos.write(buf,0,n);zos.closeEntry();
                }catch(Exception ignored){}finally{try{if(in!=null)in.close();}catch(Exception ignored){}}
            }
            zos.close();Toast.makeText(this,"Backup berhasil dibuat.",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"Backup gagal: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    void restoreFromUri(Uri uri){
        try{
            ZipInputStream zis=new ZipInputStream(getContentResolver().openInputStream(uri));ZipEntry e;String scheduleText=null;
            HashMap<String,File> audioFiles=new HashMap<>();File dir=new File(getExternalFilesDir(null),"restored_audio");dir.mkdirs();
            while((e=zis.getNextEntry())!=null){
                if("schedule.txt".equals(e.getName())){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=zis.read(b))>0)out.write(b,0,n);scheduleText=out.toString("UTF-8");}
                else if(e.getName().startsWith("audio/")){File outFile=new File(dir,new File(e.getName()).getName());FileOutputStream fos=new FileOutputStream(outFile);byte[] b=new byte[8192];int n;while((n=zis.read(b))>0)fos.write(b,0,n);fos.close();audioFiles.put(e.getName(),outFile);}
                zis.closeEntry();
            }zis.close();
            if(scheduleText==null)throw new Exception("File jadwal tidak ditemukan");
            ArrayList<Schedule> restored=new ArrayList<>();
            for(String line:scheduleText.split("\\n")){String[] p=line.split("\\|",-1);if(p.length<7)continue;String au=p[4];File af=audioFiles.get(au);String uri2=af!=null?Uri.fromFile(af).toString():null;restored.add(new Schedule(Integer.parseInt(p[0]),p[1],p[2],p[3],uri2,Integer.parseInt(p[5]),Integer.parseInt(p[6])));}
            schedules=restored;save();if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);showPage(PAGE_SCHEDULE);Toast.makeText(this,"Restore berhasil.",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"Restore gagal: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    void render(){
        list.removeAllViews();
        Schedule next=getNextSchedule();
        if(schedules.isEmpty()){list.addView(emptyState("♫","Belum ada jadwal","Tambahkan jadwal pertama Anda."));return;}
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            if(next!=null&&s.id==next.id)row.setBackground(bg(darkMode()?Color.rgb(20,83,76):Color.rgb(224,247,242)));
            LinearLayout inf=new LinearLayout(this);inf.setOrientation(LinearLayout.VERTICAL);inf.setPadding(0,0,dp(6),0);
            TextView name=tv(s.name,16);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));name.setSingleLine(false);inf.addView(name,new LinearLayout.LayoutParams(-1,dp(38)));
            TextView a=tv(s.time+"  •  "+s.audioName+"\n"+dayText(s.daysMask),12);a.setTextColor(darkMode()?Color.rgb(175,195,190):Color.rgb(90,105,100));inf.addView(a,new LinearLayout.LayoutParams(-1,dp(30)));
            row.addView(inf,new LinearLayout.LayoutParams(0,dp(82),1));
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
            LinearLayout r1=new LinearLayout(this);Button play=actionButton("▶");play.setOnClickListener(v->preview(s));r1.addView(play,new LinearLayout.LayoutParams(dp(54),dp(34)));
            Button sound=actionButton("Pilih");sound.setOnClickListener(v->chooseAudio(s.id));r1.addView(sound,new LinearLayout.LayoutParams(dp(66),dp(34)));actions.addView(r1);
            LinearLayout r2=new LinearLayout(this);Button edit=actionButton("Edit");edit.setOnClickListener(v->editSchedule(s.id));r2.addView(edit,new LinearLayout.LayoutParams(dp(60),dp(34)));
            Button del=actionButton("Hapus");del.setOnClickListener(v->deleteSchedule(s.id));r2.addView(del,new LinearLayout.LayoutParams(dp(60),dp(34)));actions.addView(r2);
            row.addView(actions,new LinearLayout.LayoutParams(dp(126),dp(72)));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(98));rp.setMargins(dp(8),dp(4),dp(8),0);list.addView(row,rp);
        }
    }

    LinearLayout emptyState(String icon,String title,String message){
        LinearLayout box=card();box.setGravity(Gravity.CENTER);box.setPadding(dp(18),dp(22),dp(18),dp(22));
        TextView i=tv(icon,40);i.setGravity(Gravity.CENTER);i.setTextColor(themeColor());box.addView(i,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView t=tv(title,17);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));t.setGravity(Gravity.CENTER);box.addView(t,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView m=tv(message,13);m.setGravity(Gravity.CENTER);box.addView(m,new LinearLayout.LayoutParams(-1,dp(42)));
        return box;
    }

    Schedule getNextSchedule(){
        Calendar now=Calendar.getInstance();Schedule best=null;Calendar bestCal=null;
        for(Schedule s:schedules){
            try{
                String[] hm=s.time.split(":");if(hm.length!=2)continue;
                int h=Integer.parseInt(hm[0]),m=Integer.parseInt(hm[1]);
                for(int off=0;off<7;off++){
                    Calendar cal=(Calendar)now.clone();cal.add(Calendar.DAY_OF_YEAR,off);
                    int bit=1<<((cal.get(Calendar.DAY_OF_WEEK)+5)%7);
                    if((s.daysMask&bit)==0)continue;
                    cal.set(Calendar.HOUR_OF_DAY,h);cal.set(Calendar.MINUTE,m);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);
                    if(cal.getTimeInMillis()<=System.currentTimeMillis())continue;
                    if(bestCal==null||cal.before(bestCal)){bestCal=cal;best=s;}break;
                }
            }catch(Exception ignored){}
        }
        return best;
    }

    CheckBox[] dayChecks;
    String[] dayNames={"Senin","Selasa","Rabu","Kamis","Jumat","Sabtu","Minggu"};

    int getDaysMask(CheckBox[] checks){
        int mask=0;for(int i=0;i<7;i++)if(checks[i].isChecked())mask|=(1<<i);return mask==0?31:mask;
    }
    String dayText(int mask){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<7;i++)if((mask&(1<<i))!=0){if(b.length()>0)b.append(", ");b.append(dayNames[i]);}
        return b.length()==0?"Tidak ada hari":b.toString();
    }
    CheckBox[] addDayChecks(LinearLayout form,int mask){
        CheckBox[] checks=new CheckBox[7];
        LinearLayout days=new LinearLayout(this);days.setOrientation(LinearLayout.VERTICAL);
        for(int r=0;r<4;r++){
            LinearLayout line=new LinearLayout(this);
            for(int col=0;col<2;col++){int i=r*2+col;if(i>=7)break;CheckBox cb=new CheckBox(this);cb.setText(dayNames[i]);cb.setTextSize(13);cb.setChecked((mask&(1<<i))!=0);line.addView(cb,new LinearLayout.LayoutParams(0,dp(38),1));checks[i]=cb;}
            days.addView(line,new LinearLayout.LayoutParams(-1,dp(38)));
        }
        form.addView(days);
        return checks;
    }

    void editSchedule(int id){
        Schedule found=null;for(Schedule s:schedules)if(s.id==id){found=s;break;}if(found==null)return;final Schedule target=found;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(4),dp(4),dp(4),0);
        EditText name=new EditText(this);name.setText(target.name);name.setHint("Nama bel");form.addView(name,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText time=new EditText(this);time.setText(target.time);time.setHint("Waktu HH:mm, ketik 1300 untuk 13:00");setupTimeInput(time);form.addView(time,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView dh=tv("Hari bel berbunyi",14);dh.setTypeface(Typeface.DEFAULT,Typeface.BOLD);form.addView(dh,new LinearLayout.LayoutParams(-1,dp(30)));
        dayChecks=addDayChecks(form,target.daysMask);
        new AlertDialog.Builder(this).setTitle("Edit Jadwal Bel").setView(form).setNeutralButton("Tes Suara",(d,w)->preview(target)).setPositiveButton("Simpan",(d,w)->{
            String n=name.getText().toString().trim(),t=time.getText().toString().trim();
            if(n.isEmpty())n="Jadwal Baru";
            if(!t.matches("([01]\\d|2[0-3]):[0-5]\\d")){Toast.makeText(this,"Format waktu harus HH:mm, contoh 13:00",Toast.LENGTH_LONG).show();return;}
            target.name=n;target.time=t;target.daysMask=getDaysMask(dayChecks);save();if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    void deleteSchedule(int id){
        Schedule found=null;for(Schedule s:schedules)if(s.id==id){found=s;break;}if(found==null)return;final Schedule target=found;
        new AlertDialog.Builder(this).setTitle("Hapus Jadwal").setMessage("Hapus jadwal \"" + target.name + "\"?").setPositiveButton("Hapus",(d,w)->{
            schedules.remove(target);save();if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    int builtInRawForName(String name){
        if(name==null)return 0;
        String n=name.toLowerCase(Locale.US);
        if(n.contains("masuk setelah")||n.contains("masuk setelah istirahat"))return R.raw.bel_masuk_istirahat;
        if(n.equals("masuk")||n.startsWith("masuk "))return R.raw.bel_masuk;
        if(n.contains("pelajaran"))return R.raw.bel_pelajaran;
        if(n.contains("istirahat"))return R.raw.bel_istirahat;
        if(n.contains("pulang"))return R.raw.bel_pulang;
        return 0;
    }

    void chooseAudio(int id){
        editingId=id;
        Schedule selected=null;for(Schedule x:schedules)if(x.id==id){selected=x;break;}if(selected==null)return;
        new AlertDialog.Builder(this).setTitle("Suara untuk "+selected.name)
        .setItems(new String[]{"Pilih file audio dari HP","Rekam suara sendiri","Hapus suara"},(d,w)->{
            if(w==0)pick();else if(w==1)record();else{Schedule s=null;for(Schedule x:schedules)if(x.id==editingId){s=x;break;}if(s==null)return;s.audioName="Belum ada suara";s.audioUri=null;s.rawId=0;save();showPage(currentPage);if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);}
        }).show();
    }

    void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("audio/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_AUDIO);
    }
    void record(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_AUDIO);return;}
        EditText e=new EditText(this);e.setHint("Nama rekaman");
        new AlertDialog.Builder(this).setTitle("Rekam suara").setView(e).setPositiveButton("Mulai",(d,w)->startRecording(e.getText().toString().trim())).setNegativeButton("Batal",null).show();
    }
    void startRecording(String name){
        if(name.isEmpty())name="Rekaman Saya";
        final String recordingName=name;
        final Schedule target=findScheduleById(editingId);
        if(target==null){
            Toast.makeText(this,"Jadwal suara tidak ditemukan.",Toast.LENGTH_SHORT).show();
            return;
        }

        try{
            File recordingDir=new File(getFilesDir(),"recordings");
            if(!recordingDir.exists()&&!recordingDir.mkdirs())throw new Exception("Folder rekaman tidak dapat dibuat");
            recordPath=new File(recordingDir,"walibell_"+System.currentTimeMillis()+".m4a").getAbsolutePath();

            // Gunakan MediaRecorder tanpa Context agar kompatibel dengan seluruh
            // rentang minSdk WaliBell dan hindari masalah lifecycle pada Android baru.
            recorder=new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(recordPath);
            recorder.prepare();
            recorder.start();

            // Dialog rekaman dibuat non-cancelable. Sebelumnya dialog dapat
            // tertutup oleh sentuhan/back sehingga onCancel() langsung memanggil
            // recorder.stop(), membuat rekaman terlihat seperti berhenti sendiri.
            final TextView status=new TextView(this);
            status.setText("Sedang merekam...\n\n00:00");
            status.setTextSize(17);
            status.setTextColor(textColor());
            status.setPadding(dp(4),dp(4),dp(4),dp(4));

            final Handler timerHandler=new Handler(Looper.getMainLooper());
            final long startedAt=SystemClock.elapsedRealtime();
            final Runnable[] ticker=new Runnable[1];
            final AlertDialog[] dialogRef=new AlertDialog[1];

            ticker[0]=()->{
                long sec=Math.max(0,(SystemClock.elapsedRealtime()-startedAt)/1000);
                long min=sec/60, s=sec%60;
                status.setText("Sedang merekam...\n\n"+String.format(Locale.getDefault(),"%02d:%02d",min,s));
                if(dialogRef[0]!=null&&dialogRef[0].isShowing())timerHandler.postDelayed(ticker[0],1000);
            };

            AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Sedang merekam")
                .setView(status)
                .setNegativeButton("BATAL",(d,w)->{
                    timerHandler.removeCallbacks(ticker[0]);
                    try{if(recorder!=null){recorder.stop();recorder.release();}}catch(Exception ignored){}
                    recorder=null;
                    try{new File(recordPath).delete();}catch(Exception ignored){}
                })
                .setPositiveButton("STOP",(d,w)->{
                    timerHandler.removeCallbacks(ticker[0]);
                    try{
                        if(recorder!=null){
                            recorder.stop();
                            recorder.release();
                        }
                        recorder=null;
                        File savedAudio=new File(recordPath);
                        if(!savedAudio.exists()||savedAudio.length()==0)throw new Exception("File rekaman tidak ditemukan");
                        target.audioName=recordingName+".m4a";
                        target.audioUri=Uri.fromFile(savedAudio).toString();
                        target.rawId=0;
                        save();
                        if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);
                        showPage(currentPage);
                        Toast.makeText(this,"Rekaman berhasil disimpan.",Toast.LENGTH_SHORT).show();
                    }catch(Exception e){
                        try{if(recorder!=null)recorder.release();}catch(Exception ignored){}
                        recorder=null;
                        try{new File(recordPath).delete();}catch(Exception ignored){}
                        Toast.makeText(this,"Rekaman gagal disimpan: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
                    }
                })
                .create();

            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d->timerHandler.removeCallbacks(ticker[0]));
            dialogRef[0]=dialog;
            dialog.show();
            timerHandler.post(ticker[0]);

        }catch(Exception e){
            try{if(recorder!=null)recorder.release();}catch(Exception ignored){}
            recorder=null;
            Toast.makeText(this,"Gagal memulai rekaman: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
        }
    }

    Schedule findScheduleById(int id){
        for(Schedule s:schedules)if(s.id==id)return s;
        return null;
    }
    void preview(Schedule s){
        MediaPlayer p=null;
        try{
            if(s.rawId==0&&(s.audioUri==null||s.audioUri.isEmpty())){
                int builtIn=builtInRawForName(s.name);
                if(builtIn!=0){
                    s.rawId=builtIn;
                    s.audioName=s.name+".wav";
                    save();
                    if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);
                }else{
                    Toast.makeText(this,"Belum ada suara. Silakan pilih atau rekam suara.",Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            if(s.rawId!=0){
                p=MediaPlayer.create(this,s.rawId);
            }else if(s.audioUri!=null&&!s.audioUri.isEmpty()){
                Uri uri=Uri.parse(s.audioUri);
                if("file".equalsIgnoreCase(uri.getScheme())){
                    // Rekaman WaliBell berada di penyimpanan internal aplikasi.
                    // Gunakan path langsung agar MediaPlayer dapat membaca file
                    // internal dengan andal pada Android 10+.
                    File audioFile=new File(uri.getPath());
                    if(!audioFile.exists()||audioFile.length()==0)
                        throw new Exception("File audio tidak ditemukan");
                    p=new MediaPlayer();
                    p.setDataSource(audioFile.getAbsolutePath());
                    p.prepare();
                }else{
                    p=MediaPlayer.create(this,uri);
                }
            }

            if(p==null)throw new Exception("MediaPlayer gagal dibuat");
            final MediaPlayer player=p;
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp,what,extra)->{
                try{mp.release();}catch(Exception ignored){}
                Toast.makeText(this,"Format atau file audio tidak dapat diputar.",Toast.LENGTH_LONG).show();
                return true;
            });
            player.start();
        }catch(Exception e){
            if(p!=null){try{p.release();}catch(Exception ignored){}}
            Toast.makeText(this,"Audio tidak dapat diputar: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==BACKUP_CREATE&&c==RESULT_OK&&d!=null&&d.getData()!=null){backupToUri(d.getData());return;}
        if(r==RESTORE_OPEN&&c==RESULT_OK&&d!=null&&d.getData()!=null){restoreFromUri(d.getData());return;}
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri u=d.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}Schedule s=null;for(Schedule x:schedules)if(x.id==editingId){s=x;break;}if(s==null)return;s.audioName=getAudioDisplayName(u);s.audioUri=u.toString();s.rawId=0;save();showPage(currentPage);if(LicenseManager.isLicensed(this)) BellReceiver.rescheduleAll(this); else BellReceiver.cancelAll(this);}
    }
    String getAudioDisplayName(Uri uri){
        try{
            if("content".equalsIgnoreCase(uri.getScheme())){
                android.database.Cursor cur=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
                if(cur!=null){
                    try{if(cur.moveToFirst()){String n=cur.getString(cur.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));if(n!=null&&!n.isEmpty())return n;}}
                    finally{cur.close();}
                }
            }
            String p=uri.getPath();
            if(p!=null){String n=new File(p).getName();if(n!=null&&!n.isEmpty())return n;}
        }catch(Exception ignored){}
        return "Audio dari HP";
    }

    void save(){getSharedPreferences("schedule",0).edit().putString("data",serialize()).apply();}
    String serialize(){StringBuilder b=new StringBuilder();for(Schedule s:schedules)b.append(s.id).append('|').append(s.time).append('|').append(s.name).append('|').append(s.audioName).append('|').append(s.audioUri==null?"":s.audioUri).append('|').append(s.rawId).append('|').append(s.daysMask).append('\n');return b.toString();}
}
