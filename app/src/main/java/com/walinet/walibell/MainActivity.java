package com.walinet.walibell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
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
    int[] raws={0,0,0,0,0};
    String[] builtinNames={};

    @Override public void onCreate(Bundle b){super.onCreate(b); load(); build(); BellReceiver.rescheduleAll(this);}

    void load(){
        schedules.clear();
        String data=getSharedPreferences("schedule",0).getString("data","");
        if(!data.isEmpty()){
            for(String line:data.split("\n")){
                try{String[] p=line.split("\\|",-1); if(p.length>=6)schedules.add(new Schedule(Integer.parseInt(p[0]),p[1],p[2],p[3],p[4].isEmpty()?null:p[4],Integer.parseInt(p[5])));}catch(Exception ignored){}
            }
        }
        if(schedules.isEmpty()) for(int i=0;i<names.length;i++) schedules.add(new Schedule(i,times[i],names[i],"Belum ada suara",null,0));
    }

    TextView tv(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(Color.rgb(23,51,43));v.setGravity(Gravity.CENTER_VERTICAL);return v;}

    void build(){
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(24,24,24,32);
        TextView title=tv("WaliBell",28); r.addView(title,new LinearLayout.LayoutParams(-1,70));
        r.addView(tv("Jadwal Bel",15),new LinearLayout.LayoutParams(-1,50));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv=new ScrollView(this); sv.addView(list); r.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(r); render();
    }

    void render(){
        list.removeAllViews();
        for(Schedule s:schedules){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(16,10,6,10);
            LinearLayout inf=new LinearLayout(this); inf.setOrientation(LinearLayout.VERTICAL);
            inf.addView(tv(s.name,17));
            TextView a=tv(s.time+"  •  "+s.audioName,12); a.setTextColor(Color.GRAY); inf.addView(a);
            row.addView(inf,new LinearLayout.LayoutParams(0,76,1));
            Button play=new Button(this); play.setText("▶"); play.setOnClickListener(v->preview(s));
            row.addView(play,new LinearLayout.LayoutParams(70,64));
            Button edit=new Button(this); edit.setText("Suara"); edit.setOnClickListener(v->chooseAudio(s.id));
            row.addView(edit,new LinearLayout.LayoutParams(100,64));
            list.addView(row,new LinearLayout.LayoutParams(-1,90));
        }
    }

    void chooseAudio(int id){
        editingId=id;
        new AlertDialog.Builder(this).setTitle("Suara untuk "+schedules.get(id).name)
            .setItems(new String[]{"Pilih suara bawaan","Pilih file audio dari HP","Rekam suara sendiri"},(d,w)->{if(w==0)builtins();else if(w==1)pick();else record();}).show();
    }

    void builtins(){
        new AlertDialog.Builder(this).setTitle("Pilih suara bawaan").setItems(builtinNames,(d,w)->{
            Schedule s=schedules.get(editingId); s.audioName=builtinNames[w]; s.audioUri=null; s.rawId=0; save(); render(); BellReceiver.rescheduleAll(this);
        }).show();
    }

    void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("audio/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,PICK_AUDIO);}

    void record(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_AUDIO);return;}
        EditText e=new EditText(this); e.setHint("Nama rekaman");
        new AlertDialog.Builder(this).setTitle("Rekam suara").setView(e).setPositiveButton("Mulai",(d,w)->startRecording(e.getText().toString().trim())).setNegativeButton("Batal",null).show();
    }

    void startRecording(String name){
        if(name.isEmpty())name="Rekaman Saya";
        try{
            recordPath=new File(getExternalFilesDir(null),"walibell_"+System.currentTimeMillis()+".m4a").getAbsolutePath();
            recorder=new MediaRecorder(this); recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setOutputFile(recordPath); recorder.prepare(); recorder.start();
            new AlertDialog.Builder(this).setTitle("Sedang merekam").setMessage("Tekan STOP setelah selesai").setPositiveButton("STOP",(d,w)->{
                try{recorder.stop();recorder.release();}catch(Exception ignored){}
                Schedule s=schedules.get(editingId); s.audioName=name; s.audioUri=Uri.fromFile(new File(recordPath)).toString(); s.rawId=0; save(); render(); BellReceiver.rescheduleAll(this);
            }).setOnCancelListener(d->{try{recorder.stop();recorder.release();}catch(Exception ignored){}}).show();
        }catch(Exception e){Toast.makeText(this,"Gagal merekam",Toast.LENGTH_SHORT).show();}
    }

    void preview(Schedule s){
        try{
            if(s.rawId==0 && (s.audioUri==null || s.audioUri.isEmpty())) { Toast.makeText(this,"Belum ada suara. Silakan pilih atau rekam suara.",Toast.LENGTH_SHORT).show(); return; }
            MediaPlayer p=s.rawId!=0?MediaPlayer.create(this,s.rawId):MediaPlayer.create(this,Uri.parse(s.audioUri));
            if(p==null){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();return;}
            p.setOnCompletionListener(MediaPlayer::release); p.start();
        }catch(Exception e){Toast.makeText(this,"Audio tidak dapat diputar",Toast.LENGTH_SHORT).show();}
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==PICK_AUDIO&&c==RESULT_OK&&d!=null&&d.getData()!=null){
            Uri u=d.getData(); try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            Schedule s=schedules.get(editingId); s.audioName="Audio dari HP"; s.audioUri=u.toString(); s.rawId=0; save(); render(); BellReceiver.rescheduleAll(this);
        }
    }

    void save(){getSharedPreferences("schedule",0).edit().putString("data",serialize()).apply();}
    String serialize(){StringBuilder b=new StringBuilder();for(Schedule s:schedules)b.append(s.id).append('|').append(s.time).append('|').append(s.name).append('|').append(s.audioName).append('|').append(s.audioUri==null?"":s.audioUri).append('|').append(s.rawId).append('\n');return b.toString();}
}
