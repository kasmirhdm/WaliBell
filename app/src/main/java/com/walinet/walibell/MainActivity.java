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

public class MainActivity extends Activity {
    static final int PICK_AUDIO=701, RECORD_AUDIO=702, BACKUP_CREATE=703, RESTORE_OPEN=704;
    static final int PAGE_HOME=0, PAGE_SCHEDULE=1, PAGE_SOUND=2, PAGE_HISTORY=3, PAGE_DEVICE=4, PAGE_ABOUT=5;
    static final int GREEN=Color.rgb(15,118,110), DARK=Color.rgb(6,78,68);
    static final int LIGHT=Color.rgb(236,253,245), CARD=Color.rgb(248,250,249);
    LinearLayout list,content,rootLayout,bottomNav;
    TextView kioskClock,kioskCountdown;
    Handler kioskHandler=new Handler(Looper.getMainLooper());
    Runnable kioskTicker;
    boolean kioskMode=false;
    ArrayList<Schedule> schedules=new ArrayList<>();
    int editingId=-1,currentPage=1;
    MediaRecorder recorder;
    String recordPath;
    int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    String[] names={"Masuk","Pelajaran 1","Istirahat","Masuk Setelah Istirahat","Pulang"};
    String[] times={"07:00","08:00","10:10","10:30","12:30"};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        load();
        build();
        BellReceiver.rescheduleAll(this);
        startBellService();
        new Handler().postDelayed(this::checkSystemProtections,700);
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
        if(schedules.isEmpty())for(int i=0;i<names.length;i++)schedules.add(new Schedule(i,times[i],names[i],"Belum ada suara",null,0));
        // Bersihkan data uji coba yang terlihat pada V3.13 dan pulihkan jadwal Masuk bawaan.
        for(Schedule s:schedules){
            if("Pelajaran Pertama".equals(s.name)&&"20:13".equals(s.time)){
                s.name="Masuk"; s.time="07:00"; s.audioName="Belum ada suara"; s.audioUri=null; s.rawId=0; s.daysMask=31;
            }
        }
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
        ImageView iv=new ImageView(this);iv.setImageResource(icon);iv.setContentDescription(label);
        item.addView(iv,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView t=tv(label,11);t.setGravity(Gravity.CENTER);item.addView(t,new LinearLayout.LayoutParams(-1,dp(20)));
        item.setOnClickListener(v->showPage(page));return item;
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
        int[] navIcons={R.drawable.ic_home,R.drawable.ic_schedule,R.drawable.ic_music,R.drawable.ic_bell,R.drawable.ic_devices,R.drawable.ic_info};
        String[] labels={"Beranda","Jadwal","Suara","Riwayat","Perangkat","Tentang"};
        for(int i=0;i<labels.length;i++){int page=(i==3)?PAGE_HISTORY:(i==4)?PAGE_DEVICE:(i==5)?PAGE_ABOUT:i;nav.addView(navItem(labels[i],navIcons[i],page),new LinearLayout.LayoutParams(0,dp(52),1));}
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

    void checkSystemProtections(){
        if(Build.VERSION.SDK_INT>=23){
            android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null&&!pm.isIgnoringBatteryOptimizations(getPackageName())){
                new AlertDialog.Builder(this).setTitle("Izinkan WaliBell berjalan di latar belakang")
                    .setMessage("Agar bel tetap berbunyi saat layar terkunci atau aplikasi ditutup, izinkan WaliBell mengabaikan optimasi baterai.")
                    .setPositiveButton("Izinkan",(d,w)->{
                        try{
                            Intent in=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            in.setData(Uri.parse("package:"+getPackageName()));startActivity(in);
                        }catch(Exception e){openAppSettings();}
                    }).setNegativeButton("Nanti",null).show();
                return;
            }
        }
        if(Build.VERSION.SDK_INT>=31){
            AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
            if(am!=null&&!am.canScheduleExactAlarms()){
                new AlertDialog.Builder(this).setTitle("Izinkan alarm tepat waktu")
                    .setMessage("WaliBell memerlukan izin alarm tepat agar bel berbunyi sesuai jadwal.")
                    .setPositiveButton("Buka Pengaturan",(d,w)->{
                        try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                        catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
                    }).setNegativeButton("Nanti",null).show();
                return;
            }
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},911);
            return;
        }
        showAutostartGuideIfNeeded();
    }

    void openAppSettings(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}
    }

    void showAutostartGuideIfNeeded(){
        if(getSharedPreferences("ui",0).getBoolean("autostart_prompt",false))return;
        getSharedPreferences("ui",0).edit().putBoolean("autostart_prompt",true).apply();
        new AlertDialog.Builder(this).setTitle("Aktifkan Autostart")
            .setMessage("Pada Xiaomi, OPPO, vivo, realme dan beberapa HP lain, aktifkan Autostart/Izinkan berjalan otomatis untuk WaliBell agar sistem tidak menghentikan aplikasi di latar belakang.")
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
            }
            if(in!=null){startActivity(in);return;}
        }catch(Exception ignored){}
        openAppSettings();
    }

    @Override protected void onResume(){
        super.onResume();
        try{BellReceiver.rescheduleAll(this);startBellService();}catch(Exception ignored){}
    }

    void showPage(int page){
        currentPage=page;
        kioskMode=false;
        if(bottomNav!=null)bottomNav.setVisibility(View.VISIBLE);
        applySystemTheme();
        if(page==PAGE_HOME)showHome();
        else if(page==PAGE_SCHEDULE)showSchedulePage();
        else if(page==PAGE_SOUND)showSoundPage();
        else if(page==PAGE_HISTORY)showHistoryPage();
        else if(page==PAGE_DEVICE)showDevicePage();
        else showAboutPage();
    }

    void showHome(){
        clearContent();
        content.addView(header("WaliBell","Bel Sekolah Otomatis"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout date=card();
        TextView d=tv("SMPN 1 Sungai Batang",15);d.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        date.addView(d,new LinearLayout.LayoutParams(-1,dp(26)));
        TextView clock=tv("Jadwal Bel",15);date.addView(clock,new LinearLayout.LayoutParams(-1,dp(25)));
        Schedule next=schedules.isEmpty()?null:schedules.get(0);
        TextView nx=tv(next==null?"Belum ada jadwal":next.name+"   •   "+next.time,25);nx.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        date.addView(nx,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,dp(112));dp.setMargins(dp(8),dp(8),dp(8),0);content.addView(date,dp);

        LinearLayout upcoming=card();upcoming.setPadding(dp(14),dp(14),dp(14),dp(12));
        TextView ut=tv("Jadwal berikutnya",15);ut.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        upcoming.addView(ut,new LinearLayout.LayoutParams(-1,dp(30)));
        upcoming.addView(tv(next==null?"Belum ada jadwal":next.name+"\n"+next.time,20),new LinearLayout.LayoutParams(-1,dp(64)));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,dp(108));up.setMargins(dp(8),dp(8),dp(8),0);content.addView(upcoming,up);

        Button manual=iconButton("Bel Manual",R.drawable.ic_bell);manual.setTextColor(Color.WHITE);manual.setBackground(stadiumBg(themeColor()));
        manual.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);if(next!=null)preview(next);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(50));mp.setMargins(dp(8),dp(8),dp(8),0);content.addView(manual,mp);
        Button kiosk=iconButton("Mode Layar Penuh",R.drawable.ic_devices);kiosk.setTextColor(Color.WHITE);kiosk.setBackground(stadiumBg(themeColor()));kiosk.setOnClickListener(v->showKioskPage());
        LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,dp(48));kp.setMargins(dp(8),dp(6),dp(8),0);content.addView(kiosk,kp);

        LinearLayout status=card();status.setPadding(dp(14),dp(14),dp(14),dp(12));
        TextView st=tv("Status aplikasi",16);st.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        status.addView(st,new LinearLayout.LayoutParams(-1,dp(30)));
        status.addView(tv("Aktif  •  Semua jadwal berjalan",14),new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(76));sp.setMargins(dp(8),dp(8),dp(8),0);content.addView(status,sp);
    }

    void showSchedulePage(){
        clearContent();content.addView(header("Jadwal Bel","Atur waktu dan suara untuk setiap bel"),new LinearLayout.LayoutParams(-1,dp(78)));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(list);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        Button add=iconButton("Tambah Jadwal",R.drawable.ic_add);add.setTextColor(Color.WHITE);add.setTextSize(14);add.setBackground(stadiumBg(themeColor()));add.setOnClickListener(v->addSchedule());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(dp(8),dp(6),dp(8),dp(8));content.addView(add,ap);render();
    }

    void addSchedule(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(4),dp(4),dp(4),0);
        EditText name=new EditText(this);name.setHint("Nama bel, misalnya Upacara");form.addView(name,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText time=new EditText(this);time.setHint("Waktu (HH:mm), misalnya 13:00");time.setInputType(2);form.addView(time,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView dh=tv("Hari bel berbunyi",14);dh.setTypeface(Typeface.DEFAULT,Typeface.BOLD);form.addView(dh,new LinearLayout.LayoutParams(-1,dp(30)));
        dayChecks=addDayChecks(form,31);
        new AlertDialog.Builder(this).setTitle("Tambah Jadwal Bel").setView(form).setPositiveButton("Simpan",(d,w)->{
            String n=name.getText().toString().trim(),t=time.getText().toString().trim();
            if(n.isEmpty())n="Jadwal Baru";
            if(!t.matches("([01]\\d|2[0-3]):[0-5]\\d")){Toast.makeText(this,"Format waktu harus HH:mm, contoh 13:00",Toast.LENGTH_LONG).show();return;}
            int id=0;for(Schedule s:schedules)id=Math.max(id,s.id+1);
            schedules.add(new Schedule(id,t,n,"Belum ada suara",null,0,getDaysMask(dayChecks)));
            save();BellReceiver.rescheduleAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    void showSoundPage(){
        clearContent();content.addView(header("Kelola Suara","Kelola suara yang digunakan setiap jadwal"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);if(schedules.isEmpty()){box.addView(emptyState("♫","Belum ada suara","Tambahkan jadwal terlebih dahulu."));}
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=tv("♫  "+s.name+"\n     "+s.audioName,14);row.addView(name,new LinearLayout.LayoutParams(0,dp(58),1));
            Button play=iconButton("",R.drawable.ic_play);play.setOnClickListener(v->preview(s));row.addView(play,new LinearLayout.LayoutParams(dp(50),dp(42)));
            Button b=actionButton("Suara");b.setOnClickListener(v->chooseAudio(s.id));row.addView(b,new LinearLayout.LayoutParams(dp(70),dp(42)));
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

    void showAboutPage(){
        clearContent();content.addView(header("Tentang","Informasi aplikasi"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout box=card();box.setGravity(Gravity.CENTER);
        TextView n=tv("WaliBell",28);n.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));n.setGravity(Gravity.CENTER);n.setTextColor(themeColor());box.addView(n,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView v=tv("Versi 4.0\n\nAplikasi bel sekolah otomatis\nMudah, Praktis, dan Andal\n\nKasmir, S.Pd\nKepala Sekolah\nSMPN 1 Sungai Batang\nIndragiri Hilir - Riau",16);v.setGravity(Gravity.CENTER);box.addView(v,new LinearLayout.LayoutParams(-1,dp(210)));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(300));bp.setMargins(dp(8),dp(8),dp(8),0);page.addView(box,bp);
        TextView quote=tv("“Disiplin dimulai dari waktu”",18);quote.setTextColor(themeColor());quote.setGravity(Gravity.CENTER);page.addView(quote,new LinearLayout.LayoutParams(-1,dp(58)));
        LinearLayout tools=card();TextView th=tv("Tampilan & Alat",17);th.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));tools.addView(th,new LinearLayout.LayoutParams(-1,dp(32)));
        Button themeBtn=actionButton("Mode Gelap / Terang");themeBtn.setOnClickListener(v2->toggleDarkMode());tools.addView(themeBtn,new LinearLayout.LayoutParams(-1,dp(44)));
        Button colorBtn=actionButton("Warna Tema");colorBtn.setOnClickListener(v2->chooseTheme());tools.addView(colorBtn,new LinearLayout.LayoutParams(-1,dp(44)));
        Button kioskBtn=actionButton("Mode Layar Penuh / Kiosk");kioskBtn.setOnClickListener(v2->showKioskPage());tools.addView(kioskBtn,new LinearLayout.LayoutParams(-1,dp(44)));
        Button backupBtn=actionButton("Backup Data Jadwal & Suara");backupBtn.setOnClickListener(v2->backupData());tools.addView(backupBtn,new LinearLayout.LayoutParams(-1,dp(44)));
        Button restoreBtn=actionButton("Restore Data");restoreBtn.setOnClickListener(v2->restoreData());tools.addView(restoreBtn,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,dp(246));tp.setMargins(dp(8),dp(8),dp(8),dp(8));page.addView(tools,tp);
        TextView tip=tv("Backup menyimpan jadwal, hari, dan file suara ke satu paket ZIP.",13);tip.setPadding(dp(12),dp(4),dp(12),dp(12));page.addView(tip,new LinearLayout.LayoutParams(-1,dp(42)));
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
            schedules=restored;save();BellReceiver.rescheduleAll(this);showPage(PAGE_SCHEDULE);Toast.makeText(this,"Restore berhasil.",Toast.LENGTH_LONG).show();
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
            Button sound=actionButton("Suara");sound.setOnClickListener(v->chooseAudio(s.id));r1.addView(sound,new LinearLayout.LayoutParams(dp(66),dp(34)));actions.addView(r1);
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
        EditText time=new EditText(this);time.setText(target.time);time.setHint("Waktu HH:mm");time.setInputType(2);form.addView(time,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView dh=tv("Hari bel berbunyi",14);dh.setTypeface(Typeface.DEFAULT,Typeface.BOLD);form.addView(dh,new LinearLayout.LayoutParams(-1,dp(30)));
        dayChecks=addDayChecks(form,target.daysMask);
        new AlertDialog.Builder(this).setTitle("Edit Jadwal Bel").setView(form).setNeutralButton("Tes Suara",(d,w)->preview(target)).setPositiveButton("Simpan",(d,w)->{
            String n=name.getText().toString().trim(),t=time.getText().toString().trim();
            if(n.isEmpty())n="Jadwal Baru";
            if(!t.matches("([01]\\d|2[0-3]):[0-5]\\d")){Toast.makeText(this,"Format waktu harus HH:mm, contoh 13:00",Toast.LENGTH_LONG).show();return;}
            target.name=n;target.time=t;target.daysMask=getDaysMask(dayChecks);save();BellReceiver.rescheduleAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    void deleteSchedule(int id){
        Schedule found=null;for(Schedule s:schedules)if(s.id==id){found=s;break;}if(found==null)return;final Schedule target=found;
        new AlertDialog.Builder(this).setTitle("Hapus Jadwal").setMessage("Hapus jadwal \"" + target.name + "\"?").setPositiveButton("Hapus",(d,w)->{
            schedules.remove(target);save();BellReceiver.rescheduleAll(this);showPage(1);
        }).setNegativeButton("Batal",null).show();
    }

    void chooseAudio(int id){
        editingId=id;
        Schedule selected=null;for(Schedule x:schedules)if(x.id==id){selected=x;break;}if(selected==null)return;
        new AlertDialog.Builder(this).setTitle("Suara untuk "+selected.name)
        .setItems(new String[]{"Pilih file audio dari HP","Rekam suara sendiri","Hapus suara"},(d,w)->{
            if(w==0)pick();else if(w==1)record();else{Schedule s=null;for(Schedule x:schedules)if(x.id==editingId){s=x;break;}if(s==null)return;s.audioName="Belum ada suara";s.audioUri=null;s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);}
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
        if(name.isEmpty())name="Rekaman Saya";final String recordingName=name;
        try{
            recordPath=new File(getExternalFilesDir(null),"walibell_"+System.currentTimeMillis()+".m4a").getAbsolutePath();
            recorder=new MediaRecorder(this);recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setOutputFile(recordPath);recorder.prepare();recorder.start();
            new AlertDialog.Builder(this).setTitle("Sedang merekam").setMessage("Tekan STOP setelah selesai").setPositiveButton("STOP",(d,w)->{
                try{recorder.stop();recorder.release();}catch(Exception ignored){}
                Schedule s=schedules.get(editingId);s.audioName=recordingName;s.audioUri=Uri.fromFile(new File(recordPath)).toString();s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);
            }).setOnCancelListener(d->{try{recorder.stop();recorder.release();}catch(Exception ignored){}}).show();
        }catch(Exception e){Toast.makeText(this,"Gagal merekam",Toast.LENGTH_SHORT).show();}
    }
    void preview(Schedule s){
        try{
            if(s.rawId==0&&(s.audioUri==null||s.audioUri.isEmpty())){Toast.makeText(this,"Belum ada suara. Silakan pilih atau rekam suara.",Toast.LENGTH_SHORT).show();return;}
            MediaPlayer p=s.rawId!=0?MediaPlayer.create(this,s.rawId):MediaPlayer.create(this,Uri.parse(s.audioUri));
            if(p==null){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();return;}p.setOnCompletionListener(MediaPlayer::release);p.start();
        }catch(Exception e){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==BACKUP_CREATE&&c==RESULT_OK&&d!=null&&d.getData()!=null){backupToUri(d.getData());return;}
        if(r==RESTORE_OPEN&&c==RESULT_OK&&d!=null&&d.getData()!=null){restoreFromUri(d.getData());return;}
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri u=d.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}Schedule s=null;for(Schedule x:schedules)if(x.id==editingId){s=x;break;}if(s==null)return;s.audioName=getAudioDisplayName(u);s.audioUri=u.toString();s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);}
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
