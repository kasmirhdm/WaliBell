import math, os, wave, struct

OUT = os.path.join("app", "src", "main", "res", "raw")
os.makedirs(OUT, exist_ok=True)
RATE = 22050

def bell(path, pattern):
    samples=[]
    for freq, start, dur, amp in pattern:
        total=int((dur+start)*RATE)
        while len(samples)<total: samples.append(0)
        for i in range(int(dur*RATE)):
            t=i/RATE
            env=math.exp(-3.2*t/dur)
            val=amp*env*(math.sin(2*math.pi*freq*t)+0.35*math.sin(2*math.pi*freq*2.01*t))
            idx=int((start+t)*RATE)
            if idx<len(samples): samples[idx]+=val
    samples=[max(-32767,min(32767,int(x*15000))) for x in samples]
    with wave.open(path,"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h",x) for x in samples))

bell(os.path.join(OUT,"bel_masuk.wav"), [(880,0,1.6,.9),(1320,.12,1.4,.65),(1760,.25,1.1,.45)])
bell(os.path.join(OUT,"bel_pelajaran.wav"), [(988,0,1.2,.85),(1480,.12,1.0,.65),(1976,.24,.8,.45)])
bell(os.path.join(OUT,"bel_istirahat.wav"), [(784,0,1.7,.9),(1175,.14,1.5,.7),(1568,.28,1.3,.5)])
bell(os.path.join(OUT,"bel_masuk_istirahat.wav"), [(660,0,1.5,.85),(990,.12,1.3,.7),(1320,.24,1.1,.5),(1760,.36,.9,.35)])
bell(os.path.join(OUT,"bel_pulang.wav"), [(523,0,1.8,.9),(784,.14,1.5,.7),(1046,.28,1.2,.5)])
