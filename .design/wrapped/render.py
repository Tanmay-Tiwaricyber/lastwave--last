from PIL import Image, ImageDraw, ImageFont

W, HEIGHT = 1080, 1920
pad = 96
Z950=(9,9,11); Z900=(24,24,27); Z800=(39,39,42)
Z500=(113,113,123); Z400=(159,159,169)
G400=(5,223,114)

sans = lambda s: ImageFont.truetype("plex_sans_regular.ttf", s)
sansb = lambda s: ImageFont.truetype("plex_sans_semibold.ttf", s)
mono = lambda s: ImageFont.truetype("plex_mono_regular.ttf", s)
monom = lambda s: ImageFont.truetype("plex_mono_medium.ttf", s)

def track(font, px):
    # PIL has no letterSpacing; approximate by drawing char by char
    class T:
        def __init__(s): s.w=0
        def draw(s, d, xy, text, fill, spacing):
            x,y=xy
            for ch in text:
                d.text((x,y), ch, font=font, fill=fill)
                x += font.getlength(ch)+spacing
            return x
    return T()

def tw(font, text, spacing=0.0):
    return sum(font.getlength(c)+spacing for c in text)

def gradient_wash(img):
    d = ImageDraw.Draw(img, "RGBA")
    for y in range(int(HEIGHT*0.62)):
        t = y/(HEIGHT*0.62)
        if t < 0.45:
            a = int(46*(1-t/0.45)) + int(20*(t/0.45)) - 20
            a = int(46 + (20-46)*(t/0.45))
        else:
            a = int(20*(1-(t-0.45)/0.55))
        d.line([(0,y),(W,y)], fill=(5,223,114,a))
    return img

def render(report):
    img = Image.new("RGB",(W,HEIGHT),Z950)
    img = gradient_wash(img)
    d = ImageDraw.Draw(img,"RGBA")
    # hairlines
    for i in range(1,6):
        y=int(HEIGHT*i/6); d.line([(0,y),(W,y)], fill=(255,255,255,20), width=2)

    # header
    e=monom(34); sp=0.12*34
    x=pad
    for ch in "LASTWAVE  ·  "+report["period"].upper():
        d.text((x,170),ch,font=e,fill=G400); x+=e.getlength(ch)+sp
    d.text((pad,268),"Wrapped",font=sansb(78),fill=(255,255,255))
    d.text((pad,322),"A recap of what you actually played.",font=mono(30),fill=Z400)

    # headline
    mins = report["totalMinutes"]
    if mins>=6000:
        big = f"{mins//60:,}"; unit="HOURS"
    else:
        big = f"{mins:,}"; unit="MINUTES"
    d.text((pad-6,640-250),"",font=sansb(10))
    d.text((pad,640-198),big,font=sansb(250),fill=(255,255,255))
    x=pad
    for ch in unit+" LISTENED":
        d.text((x,706),ch,font=monom(40),fill=G400); x+=monom(40).getlength(ch)+0.18*40

    # dynamic line wrapped
    line = report["line"]
    f=sans(40)
    words=line.split(" "); cur=""; y=772
    for w in words:
        cand=(cur+" "+w).strip()
        if f.getlength(cand)>W-pad*2:
            d.text((pad,y),cur,font=f,fill=Z400); y+=int(40*1.3); cur=w
        else: cur=cand
    d.text((pad,y),cur,font=f,fill=Z400)

    # stat grid
    top=880; cellW=(W-pad*2-24)/2; cellH=168
    for i,(lab,val) in enumerate(report["stats"]):
        col=i%2; row=i//2
        left=pad+col*(cellW+24); ty=top+row*(cellH+24)
        d.rounded_rectangle([left,ty,left+cellW,ty+cellH],radius=26,fill=Z900,outline=Z800,width=2)
        d.text((left+34,ty+52),lab,font=monom(24),fill=Z500)
        d.text((left+34,ty+128-66),val,font=sansb(66),fill=(255,255,255))

    # top tracks
    top=1270
    d.text((pad,top),"ON REPEAT",font=monom(28),fill=Z500)
    y=top+64
    for i,t in enumerate(report["tracks"][:3]):
        d.text((pad,y+44),f"0{i+1}",font=mono(32),fill=G400 if i==0 else Z500)
        d.rounded_rectangle([pad+66,y,pad+66+84,y+84],radius=14,fill=Z800)
        d.text((pad+178,y+38-32),t["title"],font=sansb(40),fill=(255,255,255))
        d.text((pad+178,y+74-26),t["artist"],font=sans(30),fill=Z400)
        mm=f"{t['ms']//60000:,}m"
        d.text((W-pad-mono(28).getlength(mm),y+60-24),mm,font=mono(28),fill=Z500)
        y+=108

    # personality
    y=1660
    d.rounded_rectangle([pad,y,W-pad,y+112],radius=26,fill=(5,223,114,26),outline=(5,223,114,64),width=3)
    d.text((pad+36,y+44-20),"YOU ARE A",font=monom(24),fill=G400)
    d.text((pad+36,y+94-42),report["personality"],font=sansb(52),fill=(255,255,255))

    # footer
    d.rectangle([pad,1812,W-pad,1814],fill=Z800)
    d.text((pad,1878-28),"LastWave",font=sansb(34),fill=(255,255,255))
    d.text((pad+176,1876-20),"BY SILENT PROGRAMMER",font=mono(24),fill=Z500)
    st="NOV 2026"
    d.text((W-pad-mono(24).getlength(st),1876-20),st,font=mono(24),fill=Z500)
    return img

# Note: PIL draw.text uses top-left origin; Canvas uses baseline. Offsets above
# subtract approximate ascent so the preview matches Compose baselines.
report=dict(
  period="2026", totalMinutes=48213,
  line="Arijit Singh led the way — 4,120 plays across 318 artists.",
  stats=[("TRACKS","4,120"),("ARTISTS","318"),("DAYS ACTIVE","241"),("LONGEST STREAK","63d")],
  tracks=[{"title":"Kesariya","artist":"Arijit Singh","ms":3_420_000},
          {"title":"Golden Hour","artist":"JVKE","ms":2_880_000},
          {"title":"Night Changes","artist":"One Direction","ms":2_310_000}],
  personality="Night owl")
render(report).save("poster_preview.png")
print("ok")
