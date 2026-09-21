package com.walinet.walibell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.graphics.Insets;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

public class MainActivity extends Activity {
    static final int PICK_AUDIO=701, RECORD_AUDIO=702;
    LinearLayout list;
    ArrayList<Schedule> schedules=new ArrayList<>();
    int editingId=-1;
    MediaRecorder recorder;
    String recordPath;
    String[] names={"Masuk","Pelajaran 1","Istirahat","Masuk Setelah Istirahat","Pulang"};
    String[] times={"07:00","08:00","10:10","10:30","12:30"};

    @Override public void onCreate(Bundle b){super.onCreate(b); load(); build(); BellReceiver.rescheduleAll(this);}

    void load(){
        schedules.clear();
        String data=getSharedPreferences("schedule",0).getString("data","");
        if(!data.isEmpty()){
            for(String line:data.split("\\n")){
                try{
                    String[] p=line.split("\\|",-1);
                    if(p.length>=6)schedules.add(new Schedule(Integer.parseInt(p[0]),p[1],p[2],p[3],p[4].isEmpty()?null:p[4],Integer.parseInt(p[5])));
                }catch(Exception ignored){}
            }
        }
        if(schedules.isEmpty()) for(int i=0;i<names.length;i++) schedules.add(new Schedule(i,times[i],names[i],"Belum ada suara",null,0));
    }

    TextView tv(String s,int z){
        TextView v=new TextView(this);
        v.setText(s); v.setTextSize(z); v.setTextColor(Color.rgb(23,51,43));
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    GradientDrawable bg(int color){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color); g.setCornerRadius(18);
        return g;
    }

    Button actionButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Color.rgb(23,51,43));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(4,0,4,0);
        b.setBackground(bg(Color.rgb(232,238,235)));
        b.setMinHeight(0); b.setMinWidth(0);
        b.setStateListAnimator(null);
        return b;
    }

    LinearLayout content;
    int currentPage=1;

    void build(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20,20,20,8);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(4,6,4,10);
        String[] labels={"⌂\nBeranda","▣\nJadwal","♫\nSuara","⚙\nPerangkat","ⓘ\nTentang"};
        for(int i=0;i<labels.length;i++){
            final int page=i;
            Button b=actionButton(labels[i]);
            b.setTextSize(11);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0,2,0,0);
            nav.addView(b,new LinearLayout.LayoutParams(0,62,1));
            b.setOnClickListener(v->showPage(page));
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT>=26){
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                top=bars.top;
                bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            root.setPadding(0,top,0,0);
            nav.setPadding(4,6,4,Math.max(10,bottom+10));
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        showPage(1);
    }

    void clearContent(){
        content.removeAllViews();
    }

    TextView heading(String title,String subtitle){
        TextView h=tv(title,26);
        h.setTypeface(null,android.graphics.Typeface.BOLD);
        content.addView(h,new LinearLayout.LayoutParams(-1,48));
        if(subtitle!=null){
            TextView s=tv(subtitle,14);
            s.setTextColor(Color.GRAY);
            content.addView(s,new LinearLayout.LayoutParams(-1,38));
        }
        return h;
    }

    void showPage(int page){
        currentPage=page;
        if(page==0) showHome();
        else if(page==1) showSchedulePage();
        else if(page==2) showSoundPage();
        else if(page==3) showDevicePage();
        else showAboutPage();
    }

    void showHome(){
        clearContent();
        heading("WaliBell","Bel Sekolah Otomatis");
        TextView clock=tv("Jadwal Bel",16);
        content.addView(clock,new LinearLayout.LayoutParams(-1,36));

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(20,16,20,16);
        card.setBackground(bg(Color.rgb(235,246,241)));
        TextView t=tv("Jadwal berikutnya",15);
        t.setTypeface(null,android.graphics.Typeface.BOLD);
        card.addView(t,new LinearLayout.LayoutParams(-1,32));
        Schedule next=schedules.size()>0?schedules.get(0):null;
        card.addView(tv(next==null?"Belum ada jadwal":next.name+"  •  "+next.time,22),new LinearLayout.LayoutParams(-1,42));
        content.addView(card,new LinearLayout.LayoutParams(-1,110));

        Button manual=actionButton("▶  Bel Manual");
        manual.setOnClickListener(v->{
            if(!schedules.isEmpty())preview(schedules.get(0));
            else Toast.makeText(this,"Belum ada jadwal",Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,58);
        mp.setMargins(0,18,0,0);
        content.addView(manual,mp);

        TextView status=tv("Status aplikasi\nAktif • Jadwal otomatis berjalan",16);
        status.setPadding(18,12,18,12);
        status.setBackground(bg(Color.rgb(245,247,246)));
        content.addView(status,new LinearLayout.LayoutParams(-1,76));
    }

    void showSchedulePage(){
        clearContent();
        heading("Jadwal Bel","Atur waktu dan suara untuk setiap bel");
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv=new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(list);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        render();
    }

    void showSoundPage(){
        clearContent();
        heading("Suara","Kelola suara yang digunakan setiap jadwal");
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for(Schedule s:schedules){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(12,6,4,6);
            row.setBackground(bg(Color.rgb(245,247,246)));
            TextView name=tv("♫  "+s.name+"\n    "+s.audioName,14);
            row.addView(name,new LinearLayout.LayoutParams(0,62,1));
            Button b=actionButton("Suara");
            b.setOnClickListener(v->chooseAudio(s.id));
            row.addView(b,new LinearLayout.LayoutParams(82,50));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,72);
            rp.setMargins(0,0,0,8);
            box.addView(row,rp);
        }
        ScrollView sv=new ScrollView(this);
        sv.addView(box);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showDevicePage(){
        clearContent();
        heading("Perangkat","Pengaturan keluaran suara");
        TextView out=tv("🔊  Output suara\n\n• Speaker HP (bawaan)\n• Bluetooth\n• USB / OTG",16);
        out.setPadding(18,12,18,12);
        out.setBackground(bg(Color.rgb(245,247,246)));
        content.addView(out,new LinearLayout.LayoutParams(-1,150));
        TextView vol=tv("Volume Bel\n\n──────────────  80%",16);
        vol.setPadding(18,12,18,12);
        content.addView(vol,new LinearLayout.LayoutParams(-1,100));
        TextView note=tv("Notifikasi\n\nBel akan memberi pemberitahuan saat jadwal diputar.",15);
        note.setPadding(18,12,18,12);
        note.setBackground(bg(Color.rgb(235,246,241)));
        content.addView(note,new LinearLayout.LayoutParams(-1,100));
    }

    void showAboutPage(){
        clearContent();
        heading("Tentang","Informasi aplikasi");
        TextView about=tv("WaliBell\n\nVersi 3.3\n\nAplikasi bel sekolah otomatis\nMudah, Praktis, dan Andal\n\nKasmir, S.Pd\nSMPN 1 Sungai Batang\nIndragiri Hilir - Riau",18);
        about.setGravity(Gravity.CENTER);
        about.setPadding(20,20,20,20);
        about.setBackground(bg(Color.rgb(235,246,241)));
        content.addView(about,new LinearLayout.LayoutParams(-1,300));
    }

    void render(){
        list.removeAllViews();
        for(Schedule s:schedules){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8,8,0,8);

            LinearLayout inf=new LinearLayout(this);
            inf.setOrientation(LinearLayout.VERTICAL);
            inf.setGravity(Gravity.CENTER_VERTICAL);

            TextView name=tv(s.name,17);
            name.setTypeface(null,android.graphics.Typeface.BOLD);
            inf.addView(name,new LinearLayout.LayoutParams(-1,34));

            TextView a=tv(s.time+"  •  "+s.audioName,12);
            a.setTextColor(Color.rgb(110,120,116));
            inf.addView(a,new LinearLayout.LayoutParams(-1,30));

            row.addView(inf,new LinearLayout.LayoutParams(0,70,1));

            Button play=actionButton("▶");
            play.setOnClickListener(v->preview(s));
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(58,50);
            pp.setMargins(6,0,6,0);
            row.addView(play,pp);

            Button edit=actionButton("Suara");
            edit.setOnClickListener(v->chooseAudio(s.id));
            LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(86,50);
            ep.setMargins(0,0,4,0);
            row.addView(edit,ep);

            list.addView(row,new LinearLayout.LayoutParams(-1,86));
        }
    }

    void chooseAudio(int id){
        editingId=id;
        new AlertDialog.Builder(this)
            .setTitle("Suara untuk "+schedules.get(id).name)
            .setItems(new String[]{"Pilih file audio dari HP","Rekam suara sendiri","Hapus suara"},(d,w)->{
                if(w==0) pick();
                else if(w==1) record();
                else {
                    Schedule s=schedules.get(editingId);
                    s.audioName="Belum ada suara"; s.audioUri=null; s.rawId=0;
                    save(); showPage(currentPage); BellReceiver.rescheduleAll(this);
                }
            }).show();
    }

    void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_AUDIO);
    }

    void record(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_AUDIO);
            return;
        }
        EditText e=new EditText(this);
        e.setHint("Nama rekaman");
        new AlertDialog.Builder(this).setTitle("Rekam suara").setView(e)
            .setPositiveButton("Mulai",(d,w)->startRecording(e.getText().toString().trim()))
            .setNegativeButton("Batal",null).show();
    }

    void startRecording(String name){
        if(name.isEmpty())name="Rekaman Saya";
        final String recordingName=name;
        try{
            recordPath=new File(getExternalFilesDir(null),"walibell_"+System.currentTimeMillis()+".m4a").getAbsolutePath();
            recorder=new MediaRecorder(this);
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recordPath);
            recorder.prepare(); recorder.start();

            new AlertDialog.Builder(this).setTitle("Sedang merekam")
                .setMessage("Tekan STOP setelah selesai")
                .setPositiveButton("STOP",(d,w)->{
                    try{recorder.stop();recorder.release();}catch(Exception ignored){}
                    Schedule s=schedules.get(editingId);
                    s.audioName=recordingName;
                    s.audioUri=Uri.fromFile(new File(recordPath)).toString();
                    s.rawId=0;
                    save(); showPage(currentPage); BellReceiver.rescheduleAll(this);
                })
                .setOnCancelListener(d->{try{recorder.stop();recorder.release();}catch(Exception ignored){}})
                .show();
        }catch(Exception e){Toast.makeText(this,"Gagal merekam",Toast.LENGTH_SHORT).show();}
    }

    void preview(Schedule s){
        try{
            if(s.rawId==0 && (s.audioUri==null || s.audioUri.isEmpty())){
                Toast.makeText(this,"Belum ada suara. Silakan pilih atau rekam suara.",Toast.LENGTH_SHORT).show();
                return;
            }
            MediaPlayer p=s.rawId!=0?MediaPlayer.create(this,s.rawId):MediaPlayer.create(this,Uri.parse(s.audioUri));
            if(p==null){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();return;}
            p.setOnCompletionListener(MediaPlayer::release); p.start();
        }catch(Exception e){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();}
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){
            Uri u=d.getData();
            try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            Schedule s=schedules.get(editingId);
            s.audioName="Audio dari HP"; s.audioUri=u.toString(); s.rawId=0;
            save(); showPage(currentPage); BellReceiver.rescheduleAll(this);
        }
    }

    void save(){getSharedPreferences("schedule",0).edit().putString("data",serialize()).apply();}
    String serialize(){
        StringBuilder b=new StringBuilder();
        for(Schedule s:schedules)b.append(s.id).append('|').append(s.time).append('|').append(s.name).append('|').append(s.audioName).append('|').append(s.audioUri==null?"":s.audioUri).append('|').append(s.rawId).append('\n');
        return b.toString();
    }
}
