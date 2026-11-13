# Pixel proof of the three v5.5.0 share posters. Mirrors WrappedPoster.kt:
# text is anchored on the baseline ("ls"/"rs"), same as Canvas.drawText.
from PIL import Image, ImageDraw, ImageFont
import numpy as np, math, random, datetime as dt, sys

W,H=1080,1920; PAD=96
Z950=(9,9,11); Z900=(24,24,27); Z800=(39,39,42); Z700=(63,63,70); Z500=(113,113,123); Z400=(159,159,169); G=(5,223,114)
WHITE=(255,255,255)
sans=lambda s: ImageFont.truetype("plex_sans_regular.ttf",s)
sansb=lambda s: ImageFont.truetype("plex_sans_semibold.ttf",s)
mono=lambda s: ImageFont.truetype("plex_mono_regular.ttf",s)
monom=lambda s: ImageFont.truetype("plex_mono_medium.ttf",s)

def ls_text(d,xy,text,font,fill,letter=0.0):
    x,y=xy
    for ch in text:
        d.text((x,y),ch,font=font,fill=fill,anchor="ls"); x+=font.getlength(ch)+letter
    return x
def ls_w(font,text,letter=0.0): return sum(font.getlength(c)+letter for c in text)
def rs_text(d,xy,text,font,fill,letter=0.0):
    ls_text(d,(xy[0]-ls_w(font,text,letter),xy[1]),text,font,fill,letter)

def backdrop(accent=G):
    yy,xx=np.mgrid[0:H,0:W]
    t=np.clip((xx/W)*0.5+(yy/(H*0.62))*0.5,0,1)
    a=np.where(t<0.45,46+(20-46)*(t/0.45),20*(1-np.clip((t-0.45)/0.55,0,1)))
    base=np.zeros((H,W,3),dtype=np.float32); base[:,:]=Z950
    al=(a/255.0)[...,None]
    img=Image.fromarray((base*(1-al)+np.array(accent,dtype=np.float32)*al).astype(np.uint8))
    d=ImageDraw.Draw(img,"RGBA")
    for x in (PAD-28,W-PAD+28): d.line([(x,120),(x,1800)],fill=(255,255,255,14),width=2)
    return img,d

def footer(d,stamp):
    d.rectangle([PAD,1812,W-PAD,1814],fill=Z800)
    d.text((PAD,1878),"LastWave",font=sansb(34),fill=WHITE,anchor="ls")
    ls_text(d,(PAD+176,1872),"BY SILENT PROGRAMMER",mono(24),Z500,0.02*24)
    d.text((W-PAD,1872),stamp,font=mono(24),fill=Z500,anchor="rs")

def cap_line(d,p0,p1,color,width):
    d.line([p0,p1],fill=color,width=int(round(width)))
    r=width/2
    for (x,y) in (p0,p1): d.ellipse([x-r,y-r,x+r,y+r],fill=color)

def fingerprint(d,cx,cy,R,hours,weekdays,color=G):
    mh=max(max(hours),1)
    for h in range(24):
        v=hours[h]/mh; a=math.radians(-90+h*15)
        r0=R*0.42; ln=R*(0.08+0.50*v)
        col=color+(int(255*(0.35+0.65*v)),) if v>0 else Z700+(255,)
        cap_line(d,(cx+math.cos(a)*r0,cy+math.sin(a)*r0),(cx+math.cos(a)*(r0+ln),cy+math.sin(a)*(r0+ln)),col,R*0.055)
    mw=max(max(weekdays),1); rr=R*0.29; sweep=360/7
    for i in range(7):
        w=weekdays[i]/mw
        col=color+(int(255*(0.3+0.7*w)),) if w>0 else Z700+(255,)
        wd=max(2,int(round(R*(0.02+0.07*w))))
        d.arc([cx-rr,cy-rr,cx+rr,cy+rr],-90+i*sweep+4,-90+(i+1)*sweep-4,fill=col,width=wd)
    r=R*0.07; d.ellipse([cx-r,cy-r,cx+r,cy+r],fill=color)

def header(d,r,title,sub):
    ls_text(d,(PAD,170),"LASTWAVE  ·  "+r["period"].upper(),monom(34),G,0.12*34)
    d.text((PAD,272),title,font=sansb(78),fill=WHITE,anchor="ls")
    d.text((PAD,328),sub,font=mono(30),fill=Z400,anchor="ls")

def clip(font,text,maxw):
    if font.getlength(text)<=maxw: return text
    while len(text)>1 and font.getlength(text+"…")>maxw: text=text[:-1]
    return text+"…"

def wrap(d,text,x,y,maxw,font,fill,maxlines=2):
    cur=""; n=0
    for w_ in text.split(" "):
        cand=(cur+" "+w_).strip()
        if font.getlength(cand)>maxw:
            d.text((x,y),cur,font=font,fill=fill,anchor="ls"); n+=1
            if n>=maxlines: return
            cur=w_; y+=int(font.size*1.3)
        else: cur=cand
    if cur: d.text((x,y),cur,font=font,fill=fill,anchor="ls")

# ── SUMMARY ────────────────────────────────────────────────────────────────
def summary(r):
    img,d=backdrop()
    header(d,r,"Wrapped","A recap of what you actually played.")
    fingerprint(d,W-PAD-116,236,116,r["hours"],r["weekdays"])
    mins=r["minutes"]; hrs=mins>=6000
    d.text((PAD-6,600),f"{mins//60:,}" if hrs else f"{mins:,}",font=sansb(250),fill=WHITE,anchor="ls")
    ls_text(d,(PAD,668),("HOURS" if hrs else "MINUTES")+" LISTENED",monom(40),G,0.18*40)
    wrap(d,r["line"],PAD,742,W-PAD*2,sans(40),Z400)
    top=840; cw=(W-PAD*2-24)/2; ch=156; gap=22
    for i,(lab,val) in enumerate(r["stats"]):
        L=PAD+(i%2)*(cw+24); ty=top+(i//2)*(ch+gap)
        d.rounded_rectangle([L,ty,L+cw,ty+ch],radius=26,fill=Z900,outline=Z800,width=2)
        ls_text(d,(L+34,ty+48),lab,monom(24),Z500,0.1*24)
        d.text((L+34,ty+120),val,font=sansb(66),fill=WHITE,anchor="ls")
    top=1246; ls_text(d,(PAD,top),"ON REPEAT",monom(28),Z500,0.12*28); y=top+44
    for i,t in enumerate(r["tracks"][:3]):
        ls_text(d,(PAD,y+40),f"0{i+1}",mono(32),G if i==0 else Z500)
        d.rounded_rectangle([PAD+66,y+2,PAD+142,y+78],radius=14,fill=Z800)
        d.text((PAD+172,y+34),clip(sansb(40),t["title"],W-PAD*2-200-172+100),font=sansb(40),fill=WHITE,anchor="ls")
        d.text((PAD+172,y+68),t["artist"],font=sans(30),fill=Z400,anchor="ls")
        d.text((W-PAD,y+54),t["time"],font=mono(28),fill=Z500,anchor="rs"); y+=100
    y=1636
    d.rounded_rectangle([PAD,y,W-PAD,y+112],radius=26,fill=G+(26,),outline=G+(64,),width=3)
    ls_text(d,(PAD+36,y+44),"YOUR ARCHETYPE",monom(24),G,0.14*24)
    d.text((PAD+36,y+94),r["archetype"],font=sansb(52),fill=WHITE,anchor="ls")
    rs_text(d,(W-PAD-36,y+90),r["code"],monom(34),G,0.06*34)
    footer(d,r["stamp"]); return img

# ── RECEIPT ────────────────────────────────────────────────────────────────
def leader(d,y,label,value,f,fv):
    xl=PAD; xr=W-PAD
    ls_text(d,(xl,y),label,f,Z400)
    d.text((xr,y),value,font=fv,fill=WHITE,anchor="rs")
    dotw=f.getlength("."); a=xl+f.getlength(label)+dotw; b=xr-fv.getlength(value)-dotw
    n=int((b-a)//dotw)
    if n>0: d.text((b,y),"."*n,font=f,fill=Z700,anchor="rs")

def receipt(r):
    img,d=backdrop()
    f=mono(34); fm=monom(34)
    x=ls_text(d,(PAD,170),"$ ",fm,G); ls_text(d,(x,170),"lastwave wrapped --"+r["period"].lower().replace(" ","-"),fm,WHITE)
    ls_text(d,(PAD,226),"# generated on this device · "+r["stamp"].lower(),mono(26),Z500)
    y=330
    for lab,val in r["receipt"]:
        leader(d,y,lab,val,f,fm); y+=54
    y=800; ls_text(d,(PAD,y),"## TOP TRACKS",fm,G,0.04*34); y+=64
    for i,t in enumerate(r["tracks"][:5]):
        ls_text(d,(PAD,y),f"0{i+1}",f,G if i==0 else Z500)
        d.text((W-PAD,y),t["time"],font=f,fill=Z500,anchor="rs")
        maxw=W-PAD*2-70-f.getlength(t["time"])-30
        name=clip(fm,t["title"],maxw)
        x=ls_text(d,(PAD+70,y),name,fm,WHITE)
        rest=clip(f," · "+t["artist"],maxw-fm.getlength(name)) if fm.getlength(name)+f.getlength(" · Ab…")<maxw else ""
        if rest: ls_text(d,(x,y),rest,f,Z500)
        y+=54
    y=1180; ls_text(d,(PAD,y),"## TOP ARTISTS",fm,G,0.04*34); y+=64
    for i,a in enumerate(r["artists"][:5]):
        ls_text(d,(PAD,y),f"0{i+1}",f,G if i==0 else Z500)
        d.text((W-PAD,y),a["time"],font=f,fill=Z500,anchor="rs")
        ls_text(d,(PAD+70,y),clip(fm,a["name"],W-PAD*2-70-f.getlength(a["time"])-30),fm,WHITE); y+=54
    y=1560; ls_text(d,(PAD,y),"## ARCHETYPE",fm,G,0.04*34)
    d.text((PAD,1632),r["archetype"],font=sansb(56),fill=WHITE,anchor="ls")
    rs_text(d,(W-PAD,1628),"["+r["code"]+"]",fm,G)
    wrap(d,r["tagline"],PAD,1690,W-PAD*2,mono(26),Z400)
    footer(d,r["stamp"]); return img

# ── CALENDAR ───────────────────────────────────────────────────────────────
def calendar(r):
    img,d=backdrop()
    header(d,r,"In days","Every square is a day. Brighter means more music.")
    cell=30; gap=6; u=cell+gap; bw=7*u-gap; colgap=(W-PAD*2-3*bw)/2; label_h=40; bh=label_h+6*u; rowgap=34
    top=430; mx=max(max(r["days"].values()),1)
    for i,(y,m) in enumerate(r["months"]):
        ox=PAD+(i%3)*(bw+colgap); oy=top+(i//3)*(bh+rowgap)
        first=dt.date(y,m,1); nxt=dt.date(y+(m==12),m%12+1,1)
        ls_text(d,(ox,oy+26),first.strftime("%b").upper(),monom(24),Z500,0.1*24)
        for k in range((nxt-first).days):
            slot=first.weekday()+k; cx=ox+(slot%7)*u; cy=oy+label_h+(slot//7)*u
            mins=r["days"].get(first+dt.timedelta(days=k),0)
            ratio=mins/mx
            lvl=0 if mins<=0 else 4 if ratio>=.75 else 3 if ratio>=.5 else 2 if ratio>=.25 else 1
            col=Z900+(255,) if lvl==0 else G+((0,77,128,191,255)[lvl],)
            d.rounded_rectangle([cx,cy,cx+cell,cy+cell],radius=7,fill=col)
    y=1606; cw=(W-PAD*2-2*20)/3
    for i,(lab,val) in enumerate(r["cal_stats"]):
        L=PAD+i*(cw+20)
        d.rounded_rectangle([L,y,L+cw,y+136],radius=24,fill=Z900,outline=Z800,width=2)
        ls_text(d,(L+26,y+44),lab,monom(21),Z500,0.1*21)
        d.text((L+26,y+106),val,font=sansb(52),fill=WHITE,anchor="ls")
    footer(d,r["stamp"]); return img

random.seed(7)
days={}
dd=dt.date(2026,1,1)
while dd<=dt.date(2026,11,12):
    wk=dd.weekday()
    if random.random()<(0.82 if wk>=4 else 0.62): days[dd]=int(random.expovariate(1/70))+3+(60 if wk==5 else 0)
    dd+=dt.timedelta(days=1)
hours=[40,22,9,2,0,0,5,30,55,48,36,30,28,34,40,46,52,60,78,96,120,150,170,110]
weekdays=[310,290,300,330,420,640,520]
r=dict(period="2026",minutes=48213,hours=hours,weekdays=weekdays,
 line="Arijit Singh led the way — 4,120 plays across 318 artists.",
 stats=[("PLAYS","4,120"),("ARTISTS","318"),("DAYS ACTIVE","241"),("LONGEST STREAK","63d")],
 tracks=[dict(title="Kesariya",artist="Arijit Singh",time="57m"),dict(title="Golden Hour",artist="JVKE",time="48m"),
         dict(title="Night Changes",artist="One Direction",time="38m"),dict(title="Tum Hi Ho (From \"Aashiqui 2\")",artist="Arijit Singh, Mithoon",time="31m"),
         dict(title="Heat Waves",artist="Glass Animals",time="27m")],
 artists=[dict(name="Arijit Singh",time="12h 4m"),dict(name="Pritam",time="8h 51m"),dict(name="The Weeknd",time="6h 12m"),
          dict(name="One Direction",time="4h 40m"),dict(name="A. R. Rahman",time="4h 02m")],
 receipt=[("MINUTES","48,213"),("PLAYS","4,120"),("TRACKS","512"),("ARTISTS","318"),("DAYS ACTIVE","241"),("LONGEST STREAK","63d"),("FINISHED","82%")],
 archetype="Midnight Devotee",code="N·L·R·F",tagline="A few artists, played again and again. You let songs play out.",
 months=[(2026,m) for m in range(1,13)],days=days,
 cal_stats=[("DAYS ACTIVE","241"),("LONGEST STREAK","63d"),("BEST DAY","Sat")],stamp="NOV 2026")
summary(r).save("p_summary.png"); receipt(r).save("p_receipt.png"); calendar(r).save("p_calendar.png")
# contact sheet for review only
sheet=Image.new("RGB",(W*3+80,H+40),(30,30,34))
for i,n in enumerate(("p_summary.png","p_receipt.png","p_calendar.png")): sheet.paste(Image.open(n),(20+i*(W+20),20))
sheet.resize((sheet.width//2,sheet.height//2),Image.LANCZOS).save("p_sheet.png"); print("ok")
