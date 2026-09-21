package com.walinet.walibell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
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
import java.util.*;

public class MainActivity extends Activity {
    static final int PICK_AUDIO=701, RECORD_AUDIO=702;
    static final int GREEN=Color.rgb(15,118,110), DARK=Color.rgb(6,78,68);
    static final int LIGHT=Color.rgb(236,253,245), CARD=Color.rgb(248,250,249);
    LinearLayout list,content;
    ArrayList<Schedule> schedules=new ArrayList<>();
    int editingId=-1,currentPage=1;
    MediaRecorder recorder;
    String recordPath;
    String[] names={"Masuk","Pelajaran 1","Istirahat","Masuk Setelah Istirahat","Pulang"};
    String[] times={"07:00","08:00","10:10","10:30","12:30"};

    @Override public void onCreate(Bundle b){super.onCreate(b);load();build();BellReceiver.rescheduleAll(this);}

    void load(){
        schedules.clear();
        String data=getSharedPreferences("schedule",0).getString("data","");
        if(!data.isEmpty()) for(String line:data.split("\\n")){
            try{
                String[] p=line.split("\\|",-1);
                if(p.length>=6)schedules.add(new Schedule(Integer.parseInt(p[0]),p[1],p[2],p[3],p[4].isEmpty()?null:p[4],Integer.parseInt(p[5])));
            }catch(Exception ignored){}
        }
        if(schedules.isEmpty())for(int i=0;i<names.length;i++)schedules.add(new Schedule(i,times[i],names[i],"Belum ada suara",null,0));
    }

    TextView tv(String s,int z){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(DARK);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    GradientDrawable bg(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(20);return g;}
    Button actionButton(String text){
        Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setTextColor(DARK);b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);b.setPadding(4,0,4,0);b.setBackground(bg(Color.rgb(231,243,240)));
        b.setMinHeight(0);b.setMinWidth(0);b.setStateListAnimator(null);return b;
    }
    TextView title(String text){
        TextView v=tv(text,26);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setTextColor(Color.WHITE);return v;
    }
    TextView subWhite(String text){TextView v=tv(text,14);v.setTextColor(Color.WHITE);return v;}

    LinearLayout header(String t,String s){
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.VERTICAL);h.setPadding(20,14,20,16);h.setBackground(bg(GREEN));
        h.addView(title(t),new LinearLayout.LayoutParams(-1,38));h.addView(subWhite(s),new LinearLayout.LayoutParams(-1,28));return h;
    }

    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundColor(Color.WHITE);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER);nav.setPadding(4,6,4,10);
        String[] labels={"⌂\nBeranda","▣\nJadwal","♫\nSuara","⚙\nPerangkat","ⓘ\nTentang"};
        for(int i=0;i<labels.length;i++){final int page=i;Button b=actionButton(labels[i]);b.setTextSize(11);b.setGravity(Gravity.CENTER);b.setPadding(0,2,0,0);nav.addView(b,new LinearLayout.LayoutParams(0,62,1));b.setOnClickListener(v->showPage(page));}
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            root.setPadding(0,top,0,0);nav.setPadding(4,6,4,Math.max(10,bottom+10));return insets;
        });
        setContentView(root);root.requestApplyInsets();showPage(0);
    }

    void clearContent(){content.removeAllViews();}
    LinearLayout card(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(16,14,16,14);c.setBackground(bg(CARD));return c;
    }
    void addGap(int h){Space s=new Space(this);content.addView(s,new LinearLayout.LayoutParams(1,h));}

    void showPage(int page){
        currentPage=page;
        if(page==0)showHome();else if(page==1)showSchedulePage();else if(page==2)showSoundPage();else if(page==3)showDevicePage();else showAboutPage();
    }

    void showHome(){
        clearContent();content.addView(header("WaliBell","Bel Sekolah Otomatis"),new LinearLayout.LayoutParams(-1,92));
        LinearLayout date=card();TextView d=tv("SMPN 1 Sungai Batang",15);d.setTypeface(Typeface.DEFAULT,Typeface.BOLD);date.addView(d,new LinearLayout.LayoutParams(-1,32));
        TextView clock=tv("Jadwal Bel",15);date.addView(clock,new LinearLayout.LayoutParams(-1,30));
        Schedule next=schedules.isEmpty()?null:schedules.get(0);TextView nx=tv(next==null?"Belum ada jadwal":next.name+"   •   "+next.time,25);nx.setTypeface(Typeface.DEFAULT,Typeface.BOLD);date.addView(nx,new LinearLayout.LayoutParams(-1,48));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,125);dp.setMargins(12,12,12,0);content.addView(date,dp);
        LinearLayout upcoming=card();TextView ut=tv("Jadwal berikutnya",15);ut.setTypeface(Typeface.DEFAULT,Typeface.BOLD);upcoming.addView(ut,new LinearLayout.LayoutParams(-1,30));
        upcoming.addView(tv(next==null?"Belum ada jadwal":next.name+"\n"+next.time,20),new LinearLayout.LayoutParams(-1,70));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,112);up.setMargins(12,12,12,0);content.addView(upcoming,up);
        Button manual=actionButton("▶   Bel Manual");manual.setTextColor(Color.WHITE);manual.setBackground(bg(Color.rgb(13,148,136)));manual.setOnClickListener(v->{if(next!=null)preview(next);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,56);mp.setMargins(12,12,12,0);content.addView(manual,mp);
        LinearLayout status=card();TextView st=tv("Status aplikasi",16);st.setTypeface(Typeface.DEFAULT,Typeface.BOLD);status.addView(st,new LinearLayout.LayoutParams(-1,30));status.addView(tv("Aktif  •  Semua jadwal berjalan",14),new LinearLayout.LayoutParams(-1,30));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,76);sp.setMargins(12,12,12,0);content.addView(status,sp);
    }

    void showSchedulePage(){
        clearContent();content.addView(header("Jadwal Bel","Atur waktu dan suara untuk setiap bel"),new LinearLayout.LayoutParams(-1,92));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(list);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));render();
    }

    void showSoundPage(){
        clearContent();content.addView(header("Kelola Suara","Kelola suara yang digunakan setiap jadwal"),new LinearLayout.LayoutParams(-1,92));
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=tv("♫  "+s.name+"\n     "+s.audioName,14);row.addView(name,new LinearLayout.LayoutParams(0,68,1));
            Button play=actionButton("▶");play.setOnClickListener(v->preview(s));row.addView(play,new LinearLayout.LayoutParams(54,48));
            Button b=actionButton("Suara");b.setOnClickListener(v->chooseAudio(s.id));row.addView(b,new LinearLayout.LayoutParams(76,48));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,78);rp.setMargins(10,6,10,0);box.addView(row,rp);
        }
        Button add=actionButton("+  Tambah Suara Baru");add.setTextColor(Color.WHITE);add.setBackground(bg(Color.rgb(13,148,136)));add.setOnClickListener(v->{if(!schedules.isEmpty())chooseAudio(schedules.get(0).id);});
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,54);ap.setMargins(12,12,12,12);box.addView(add,ap);
        ScrollView sv=new ScrollView(this);sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showDevicePage(){
        clearContent();content.addView(header("Perangkat","Pengaturan keluaran suara"),new LinearLayout.LayoutParams(-1,92));
        LinearLayout out=card();TextView ot=tv("Output Suara",17);ot.setTypeface(Typeface.DEFAULT,Typeface.BOLD);out.addView(ot,new LinearLayout.LayoutParams(-1,36));
        RadioGroup rg=new RadioGroup(this);String[] opts={"Speaker HP (bawaan)","Bluetooth Speaker","Perangkat USB / OTG"};for(String x:opts){RadioButton rb=new RadioButton(this);rb.setText(x);rb.setTextSize(15);rb.setTextColor(DARK);rg.addView(rb,new RadioGroup.LayoutParams(-1,42));if(x.startsWith("Speaker"))rb.setChecked(true);}out.addView(rg,new LinearLayout.LayoutParams(-1,140));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,190);op.setMargins(12,12,12,0);content.addView(out,op);
        LinearLayout vol=card();vol.addView(tv("Volume Bel",17),new LinearLayout.LayoutParams(-1,34));SeekBar sb=new SeekBar(this);sb.setProgress(80);vol.addView(sb,new LinearLayout.LayoutParams(-1,48));LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,95);vp.setMargins(12,10,12,0);content.addView(vol,vp);
        LinearLayout note=card();TextView nt=tv("Notifikasi\nTampilkan notifikasi saat bel diputar",15);note.addView(nt,new LinearLayout.LayoutParams(-1,64));Switch sw=new Switch(this);sw.setChecked(true);sw.setText("Aktif");note.addView(sw,new LinearLayout.LayoutParams(-1,48));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,120);np.setMargins(12,10,12,0);content.addView(note,np);
    }

    void showAboutPage(){
        clearContent();content.addView(header("Tentang","Informasi aplikasi"),new LinearLayout.LayoutParams(-1,92));
        LinearLayout box=card();box.setGravity(Gravity.CENTER);TextView n=tv("WaliBell",28);n.setTypeface(Typeface.DEFAULT,Typeface.BOLD);n.setGravity(Gravity.CENTER);box.addView(n,new LinearLayout.LayoutParams(-1,48));TextView v=tv("Versi 3.6\n\nAplikasi bel sekolah otomatis\nMudah, Praktis, dan Andal\n\nKasmir, S.Pd\nKepala Sekolah\nSMPN 1 Sungai Batang\nIndragiri Hilir - Riau",16);v.setGravity(Gravity.CENTER);box.addView(v,new LinearLayout.LayoutParams(-1,220));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,290);bp.setMargins(12,14,12,0);content.addView(box,bp);
        TextView quote=tv("“Disiplin dimulai dari waktu”",18);quote.setTextColor(GREEN);quote.setGravity(Gravity.CENTER);content.addView(quote,new LinearLayout.LayoutParams(-1,70));
    }

    void render(){
        list.removeAllViews();
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout inf=new LinearLayout(this);inf.setOrientation(LinearLayout.VERTICAL);
            TextView name=tv(s.name,16);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);inf.addView(name,new LinearLayout.LayoutParams(-1,32));
            TextView a=tv(s.time+"  •  "+s.audioName,13);a.setTextColor(Color.rgb(90,105,100));inf.addView(a,new LinearLayout.LayoutParams(-1,30));
            row.addView(inf,new LinearLayout.LayoutParams(0,66,1));
            Button play=actionButton("▶");play.setOnClickListener(v->preview(s));row.addView(play,new LinearLayout.LayoutParams(52,46));
            Button edit=actionButton("Suara");edit.setOnClickListener(v->chooseAudio(s.id));row.addView(edit,new LinearLayout.LayoutParams(78,46));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,82);rp.setMargins(10,5,10,0);list.addView(row,rp);
        }
    }

    void chooseAudio(int id){
        editingId=id;
        new AlertDialog.Builder(this).setTitle("Suara untuk "+schedules.get(id).name)
        .setItems(new String[]{"Pilih file audio dari HP","Rekam suara sendiri","Hapus suara"},(d,w)->{
            if(w==0)pick();else if(w==1)record();else{Schedule s=schedules.get(editingId);s.audioName="Belum ada suara";s.audioUri=null;s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);}
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
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri u=d.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}Schedule s=schedules.get(editingId);s.audioName="Audio dari HP";s.audioUri=u.toString();s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);}
    }
    void save(){getSharedPreferences("schedule",0).edit().putString("data",serialize()).apply();}
    String serialize(){StringBuilder b=new StringBuilder();for(Schedule s:schedules)b.append(s.id).append('|').append(s.time).append('|').append(s.name).append('|').append(s.audioName).append('|').append(s.audioUri==null?"":s.audioUri).append('|').append(s.rawId).append('\n');return b.toString();}
}
