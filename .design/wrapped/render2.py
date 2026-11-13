from PIL import Image, ImageDraw, ImageFont
import numpy as np

W,H = 1080,1920
pad=96
Z950=(9,9,11); Z900=(24,24,27); Z800=(39,39,42)
Z500=(113,113,123); Z400=(159,159,169); G400=(5,223,114)
sans=lambda s: ImageFont.truetype("plex_sans_regular.ttf",s)
sansb=lambda s: ImageFont.truetype("plex_sans_semibold.ttf",s)
mono=lambda s: ImageFont.truetype("plex_mono_regular.ttf",s)
monom=lambda s: ImageFont.truetype("plex_mono_medium.ttf",s)

def ls_text(d, xy, text, font, fill, letter=0.0):
    """Canvas baseline + letterSpacing emulation."""
    x,y=xy
    for ch in text:
        d.text((x,y),ch,font=font,fill=fill,anchor="ls")
        x += font.getlength(ch)+letter
    return x

def ls_width(font,text,letter=0.0):
    return sum(font.getlength(c)+letter for c in text)

def wash(img):
    # diagonal linear gradient (0,0)->(W,0.62H), green 0.18 -> 0.08 -> 0
    yy,xx=np.mgrid[0:H,0:W]
    t=(xx/W)*0.5 + (yy/(H*0.62))*0.5
    t=np.clip(t,0,1)
    a=np.where(t<0.45, 46+(20-46)*(t/0.45), 20*(1-np.clip((t-0.45)/0.55,0,1)))
    base=np.zeros((H,W,3),dtype=np.float32); base[:,:]=Z950
    green=np.array(G400,dtype=np.float32)
    al=(a/255.0)[...,None]
    out=base*(1-al)+green*al
    return Image.fromarray(out.astype(np.uint8))

def render(r):
    img=wash(img=None) if False else wash(Image.new("RGB",(W,H)))
    d=ImageDraw.Draw(img,"RGBA")
    # vertical rails at the margins — never collide with type
    for x in (pad-28, W-pad+28):
        d.line([(x,120),(x,1800)],fill=(255,255,255,14),width=2)

    ls_text(d,(pad,170),"LASTWAVE  ·  "+r["period"].upper(),monom(34),G400,0.12*34)
    d.text((pad,272),"Wrapped",font=sansb(78),fill=(255,255,255),anchor="ls")
    d.text((pad,328),"A recap of what you actually played.",font=mono(30),fill=Z400,anchor="ls")

    mins=r["minutes"]
    big=f"{mins//60:,}" if mins>=6000 else f"{mins:,}"
    unit="HOURS" if mins>=6000 else "MINUTES"
    d.text((pad-6,600),big,font=sansb(250),fill=(255,255,255),anchor="ls")
    ls_text(d,(pad,668),unit+" LISTENED",monom(40),G400,0.18*40)

    f=sans(40); words=r["line"].split(" "); cur=""; y=742
    for w in words:
        cand=(cur+" "+w).strip()
        if f.getlength(cand)>W-pad*2:
            d.text((pad,y),cur,font=f,fill=Z400,anchor="ls"); y+=int(40*1.3); cur=w
        else: cur=cand
    d.text((pad,y),cur,font=f,fill=Z400,anchor="ls")

    top=840; cw=(W-pad*2-24)/2; ch=156; gap=22
    for i,(lab,val) in enumerate(r["stats"]):
        col,row=i%2,i//2
        L=pad+col*(cw+24); ty=top+row*(ch+gap)
        d.rounded_rectangle([L,ty,L+cw,ty+ch],radius=26,fill=Z900,outline=Z800,width=2)
        ls_text(d,(L+34,ty+48),lab,monom(24),Z500,0.1*24)
        d.text((L+34,ty+120),val,font=sansb(66),fill=(255,255,255),anchor="ls")

    top=1246
    ls_text(d,(pad,top),"ON REPEAT",monom(28),Z500,0.12*28)
    y=top+44
    for i,t in enumerate(r["tracks"][:3]):
        ls_text(d,(pad,y+40),f"0{i+1}",mono(32),G400 if i==0 else Z500,0)
        d.rounded_rectangle([pad+66,y+2,pad+142,y+78],radius=14,fill=Z800)
        d.text((pad+172,y+34),t["title"],font=sansb(40),fill=(255,255,255),anchor="ls")
        d.text((pad+172,y+68),t["artist"],font=sans(30),fill=Z400,anchor="ls")
        mm=f"{t['ms']//60000:,}m"
        d.text((W-pad,y+54),mm,font=mono(28),fill=Z500,anchor="rs")
        y+=100

    y=1636
    d.rounded_rectangle([pad,y,W-pad,y+112],radius=26,fill=(5,223,114,26),outline=(5,223,114,64),width=3)
    ls_text(d,(pad+36,y+44),"YOU ARE A",monom(24),G400,0.14*24)
    d.text((pad+36,y+94),r["personality"],font=sansb(52),fill=(255,255,255),anchor="ls")

    d.rectangle([pad,1812,W-pad,1814],fill=Z800)
    d.text((pad,1878),"LastWave",font=sansb(34),fill=(255,255,255),anchor="ls")
    ls_text(d,(pad+176,1872),"BY SILENT PROGRAMMER",mono(24),Z500,0.02*24)
    d.text((W-pad,1872),r["stamp"],font=mono(24),fill=Z500,anchor="rs")
    return img

r=dict(period="2026",minutes=48213,
 line="Arijit Singh led the way — 4,120 plays across 318 artists.",
 stats=[("PLAYS","4,120"),("ARTISTS","318"),("DAYS ACTIVE","241"),("LONGEST STREAK","63d")],
 tracks=[{"title":"Kesariya","artist":"Arijit Singh","ms":3_420_000},
         {"title":"Golden Hour","artist":"JVKE","ms":2_880_000},
         {"title":"Night Changes","artist":"One Direction","ms":2_310_000}],
 personality="Night owl",stamp="NOV 2026")
render(r).save("poster_v2.png")
print("ok")
