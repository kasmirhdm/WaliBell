package com.walinet.walibell;

public class Schedule {
    public int id;
    public String time,name,audioName,audioUri;
    public int rawId;
    // Bit 0=Senin ... Bit 6=Minggu
    public int daysMask;

    public Schedule(int id,String time,String name,String audioName,String audioUri,int rawId){
        this(id,time,name,audioName,audioUri,rawId,31);
    }
    public Schedule(int id,String time,String name,String audioName,String audioUri,int rawId,int daysMask){
        this.id=id;this.time=time;this.name=name;this.audioName=audioName;this.audioUri=audioUri;this.rawId=rawId;this.daysMask=daysMask;
    }
}
