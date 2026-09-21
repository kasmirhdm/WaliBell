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
    int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    String[] names={"Masuk","Pelajaran 1","Istirahat","Masuk Setelah Istirahat","Pulang"};
    String[] times={"07:00","08:00","10:10","10:30","12:30"};

    @Override public void onCreate(Bundle b){super.onCreate(b);load();build();BellReceiver.rescheduleAll(this);}

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
    }

    TextView tv(String s,int z){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(DARK);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    GradientDrawable bg(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(14));return g;}
    Button actionButton(String text){
        Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setTextColor(DARK);b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);b.setPadding(dp(4),0,dp(4),0);b.setBackground(bg(Color.rgb(231,243,240)));
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
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.VERTICAL);h.setPadding(dp(16),dp(10),dp(16),dp(10));h.setBackground(bg(GREEN));
        h.addView(title(t),new LinearLayout.LayoutParams(-1,dp(34)));h.addView(subWhite(s),new LinearLayout.LayoutParams(-1,dp(22)));return h;
    }

    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundColor(Color.WHITE);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(4),dp(4),dp(4),dp(4));
        int[] navIcons={R.drawable.ic_home,R.drawable.ic_schedule,R.drawable.ic_music,R.drawable.ic_devices,R.drawable.ic_info};
        String[] labels={"Beranda","Jadwal","Suara","Perangkat","Tentang"};
        for(int i=0;i<labels.length;i++)nav.addView(navItem(labels[i],navIcons[i],i),new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(nav,new LinearLayout.LayoutParams(-1,dp(62)));
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            root.setPadding(0,top,0,bottom);nav.setPadding(dp(4),dp(2),dp(4),dp(2));nav.getLayoutParams().height=dp(62);nav.requestLayout();return insets;
        });
        setContentView(root);root.requestApplyInsets();showPage(0);
    }

    void clearContent(){content.removeAllViews();}
    LinearLayout card(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(bg(CARD));return c;
    }
    void addGap(int h){Space s=new Space(this);content.addView(s,new LinearLayout.LayoutParams(1,h));}

    void showPage(int page){
        currentPage=page;
        if(page==0)showHome();else if(page==1)showSchedulePage();else if(page==2)showSoundPage();else if(page==3)showDevicePage();else showAboutPage();
    }

    void showHome(){
        clearContent();content.addView(header("WaliBell","Bel Sekolah Otomatis"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout date=card();TextView d=tv("SMPN 1 Sungai Batang",15);d.setTypeface(Typeface.DEFAULT,Typeface.BOLD);date.addView(d,new LinearLayout.LayoutParams(-1,dp(26)));
        TextView clock=tv("Jadwal Bel",15);date.addView(clock,new LinearLayout.LayoutParams(-1,dp(25)));
        Schedule next=schedules.isEmpty()?null:schedules.get(0);TextView nx=tv(next==null?"Belum ada jadwal":next.name+"   •   "+next.time,25);nx.setTypeface(Typeface.DEFAULT,Typeface.BOLD);date.addView(nx,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,dp(112));dp.setMargins(dp(8),dp(8),dp(8),0);content.addView(date,dp);
        LinearLayout upcoming=card();TextView ut=tv("Jadwal berikutnya",15);ut.setTypeface(Typeface.DEFAULT,Typeface.BOLD);upcoming.addView(ut,new LinearLayout.LayoutParams(-1,30));
        upcoming.addView(tv(next==null?"Belum ada jadwal":next.name+"\n"+next.time,20),new LinearLayout.LayoutParams(-1,dp(58)));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,dp(96));up.setMargins(dp(8),dp(8),dp(8),0);content.addView(upcoming,up);
        Button manual=iconButton("Bel Manual",R.drawable.ic_bell);manual.setTextColor(Color.WHITE);manual.setBackground(bg(Color.rgb(13,148,136)));manual.setOnClickListener(v->{if(next!=null)preview(next);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(48));mp.setMargins(dp(8),dp(8),dp(8),0);content.addView(manual,mp);
        LinearLayout status=card();TextView st=tv("Status aplikasi",16);st.setTypeface(Typeface.DEFAULT,Typeface.BOLD);status.addView(st,new LinearLayout.LayoutParams(-1,30));status.addView(tv("Aktif  •  Semua jadwal berjalan",14),new LinearLayout.LayoutParams(-1,30));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(66));sp.setMargins(dp(8),dp(8),dp(8),0);content.addView(status,sp);
    }

    void showSchedulePage(){
        clearContent();content.addView(header("Jadwal Bel","Atur waktu dan suara untuk setiap bel"),new LinearLayout.LayoutParams(-1,dp(78)));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(list);
        content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        Button add=iconButton("Tambah Jadwal",R.drawable.ic_add);add.setTextColor(Color.WHITE);add.setTextSize(14);add.setBackground(bg(Color.rgb(13,148,136)));add.setOnClickListener(v->addSchedule());
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
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=tv("♫  "+s.name+"\n     "+s.audioName,14);row.addView(name,new LinearLayout.LayoutParams(0,dp(58),1));
            Button play=iconButton("",R.drawable.ic_play);play.setOnClickListener(v->preview(s));row.addView(play,new LinearLayout.LayoutParams(dp(50),dp(42)));
            Button b=actionButton("Suara");b.setOnClickListener(v->chooseAudio(s.id));row.addView(b,new LinearLayout.LayoutParams(dp(70),dp(42)));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(70));rp.setMargins(dp(8),dp(4),dp(8),0);box.addView(row,rp);
        }
        Button add=iconButton("Tambah Suara Baru",R.drawable.ic_add);add.setTextColor(Color.WHITE);add.setBackground(bg(Color.rgb(13,148,136)));add.setOnClickListener(v->{if(!schedules.isEmpty())chooseAudio(schedules.get(0).id);});
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(48));ap.setMargins(dp(8),dp(8),dp(8),dp(8));box.addView(add,ap);
        ScrollView sv=new ScrollView(this);sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showDevicePage(){
        clearContent();content.addView(header("Perangkat","Pengaturan keluaran suara"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout out=card();TextView ot=tv("Output Suara",17);ot.setTypeface(Typeface.DEFAULT,Typeface.BOLD);ot.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_volume,0,0,0);ot.setCompoundDrawablePadding(8);out.addView(ot,new LinearLayout.LayoutParams(-1,dp(32)));
        RadioGroup rg=new RadioGroup(this);String[] opts={"Speaker HP (bawaan)","Bluetooth Speaker","Perangkat USB / OTG"};for(String x:opts){RadioButton rb=new RadioButton(this);rb.setText(x);rb.setTextSize(15);rb.setTextColor(DARK);rg.addView(rb,new RadioGroup.LayoutParams(-1,dp(38)));if(x.startsWith("Speaker"))rb.setChecked(true);}out.addView(rg,new LinearLayout.LayoutParams(-1,dp(120)));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(180));op.setMargins(dp(8),dp(8),dp(8),0);content.addView(out,op);
        LinearLayout vol=card();vol.addView(tv("Volume Bel",17),new LinearLayout.LayoutParams(-1,dp(30)));SeekBar sb=new SeekBar(this);sb.setProgress(80);vol.addView(sb,new LinearLayout.LayoutParams(-1,dp(42)));LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,dp(82));vp.setMargins(dp(8),dp(6),dp(8),0);content.addView(vol,vp);
        LinearLayout note=card();TextView nt=tv("Notifikasi\nTampilkan notifikasi saat bel diputar",15);nt.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bell,0,0,0);nt.setCompoundDrawablePadding(8);note.addView(nt,new LinearLayout.LayoutParams(-1,dp(58)));Switch sw=new Switch(this);sw.setChecked(true);sw.setText("Aktif");note.addView(sw,new LinearLayout.LayoutParams(-1,dp(42)));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(112));np.setMargins(dp(8),dp(6),dp(8),0);content.addView(note,np);
    }

    void showAboutPage(){
        clearContent();content.addView(header("Tentang","Informasi aplikasi"),new LinearLayout.LayoutParams(-1,dp(78)));
        LinearLayout box=card();box.setGravity(Gravity.CENTER);TextView n=tv("WaliBell",28);n.setTypeface(Typeface.DEFAULT,Typeface.BOLD);n.setGravity(Gravity.CENTER);box.addView(n,new LinearLayout.LayoutParams(-1,48));TextView v=tv("Versi 3.11\n\nAplikasi bel sekolah otomatis\nMudah, Praktis, dan Andal\n\nKasmir, S.Pd\nKepala Sekolah\nSMPN 1 Sungai Batang\nIndragiri Hilir - Riau",16);v.setGravity(Gravity.CENTER);box.addView(v,new LinearLayout.LayoutParams(-1,dp(210)));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(300));bp.setMargins(dp(8),dp(8),dp(8),0);content.addView(box,bp);
        TextView quote=tv("“Disiplin dimulai dari waktu”",18);quote.setTextColor(GREEN);quote.setGravity(Gravity.CENTER);content.addView(quote,new LinearLayout.LayoutParams(-1,dp(58)));
    }

    void render(){
        list.removeAllViews();
        for(Schedule s:schedules){
            LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout inf=new LinearLayout(this);inf.setOrientation(LinearLayout.VERTICAL);inf.setPadding(0,0,dp(6),0);
            TextView name=tv(s.name,16);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);name.setSingleLine(false);inf.addView(name,new LinearLayout.LayoutParams(-1,dp(38)));
            TextView a=tv(s.time+"  •  "+s.audioName+"\n"+dayText(s.daysMask),12);a.setTextColor(Color.rgb(90,105,100));inf.addView(a,new LinearLayout.LayoutParams(-1,dp(30)));
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
        new AlertDialog.Builder(this).setTitle("Edit Jadwal Bel").setView(form).setPositiveButton("Simpan",(d,w)->{
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
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){Uri u=d.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}Schedule s=null;for(Schedule x:schedules)if(x.id==editingId){s=x;break;}if(s==null)return;s.audioName="Audio dari HP";s.audioUri=u.toString();s.rawId=0;save();showPage(currentPage);BellReceiver.rescheduleAll(this);}
    }
    void save(){getSharedPreferences("schedule",0).edit().putString("data",serialize()).apply();}
    String serialize(){StringBuilder b=new StringBuilder();for(Schedule s:schedules)b.append(s.id).append('|').append(s.time).append('|').append(s.name).append('|').append(s.audioName).append('|').append(s.audioUri==null?"":s.audioUri).append('|').append(s.rawId).append('\n');return b.toString();}
}
