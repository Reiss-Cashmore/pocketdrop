"""Generates art/pocketdrop.svg and the app drawables (ic_logo, ic_launcher_foreground,
ic_launcher_monochrome) from one set of shapes. Run from the scratch dir, then copy the files:
the SVG to art/, the XML files to app/src/main/res/drawable/."""
import math
# Geometry on a 96x96 canvas.
DROP = "M48,14 C48,14 34.5,30 34.5,41.5 A13.5,13.5 0 0,0 61.5,41.5 C61.5,30 48,14 48,14 Z"
DROP_SHINE = "M42.5,33 C40.8,36 40,38.6 40,41"
POCKET = "M22,46 Q48,50 74,46 Q75.6,45.8 75.4,47.4 L73.4,67 Q73.2,69 71.4,70 L49.6,82.6 Q48,83.5 46.4,82.6 L24.6,70 Q22.8,69 22.6,67 L20.6,47.4 Q20.4,45.8 22,46 Z"
RIM = "M22,46 Q48,50 74,46 Q75.6,45.8 75.4,47.4 L75,51.5 Q48,55.5 21,51.5 L20.6,47.4 Q20.4,45.8 22,46 Z"

def bez(p0,p1,p2,p3,t):
    u=1-t
    return tuple(u**3*a+3*u*u*t*b+3*u*t*t*c+t**3*d for a,b,c,d in zip(p0,p1,p2,p3))
# Stitch outline inside the pocket, as short dashes (VectorDrawable has no dasharray).
pts=[]
def line(a,b,n):
    for i in range(n): pts.append(tuple(a[k]+(b[k]-a[k])*i/n for k in range(2)))
def curve(p0,p1,p2,p3,n):
    for i in range(n): pts.append(bez(p0,p1,p2,p3,i/n))
line((69.6,57),(68.2,66),8)
line((68.2,66),(48,77.6),20)
line((48,77.6),(27.8,66),20)
line((27.8,66),(26.4,57),8)
pts.append((26.4,57))
# resample by arc length
seg=[0]
for a,b in zip(pts,pts[1:]): seg.append(seg[-1]+math.dist(a,b))
L=seg[-1]
def at(s):
    for i in range(len(seg)-1):
        if seg[i+1]>=s:
            f=(s-seg[i])/max(seg[i+1]-seg[i],1e-9)
            a,b=pts[i],pts[i+1]
            return (a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f)
    return pts[-1]
dash,gap=3.2,2.6
n=int(L/(dash+gap))
step=L/n
d=[]
for i in range(n):
    s=i*step+gap/2
    a=at(s); b=at(s+dash)
    d.append(f"M{a[0]:.2f},{a[1]:.2f} L{b[0]:.2f},{b[1]:.2f}")
STITCH=" ".join(d)

def star(cx,cy,r):
    k=r*0.28
    return (f"M{cx},{cy-r} C{cx+k*0.4},{cy-k} {cx+k},{cy-k*0.4} {cx+r},{cy} "
            f"C{cx+k},{cy+k*0.4} {cx+k*0.4},{cy+k} {cx},{cy+r} "
            f"C{cx-k*0.4},{cy+k} {cx-k},{cy+k*0.4} {cx-r},{cy} "
            f"C{cx-k},{cy-k*0.4} {cx-k*0.4},{cy-k} {cx},{cy-r} Z")
SPARKS=[star(76,23,6),star(22,30,4),star(80,35,2.8)]
MOTION="M30,14 L30,24 M66,32 L66,38 M25,42 L25,40"
MOTION="M32.5,18 L32.5,27 M63.5,18 L63.5,25"
RIVETS="M22.5,49 m-2.2,0 a2.2,2.2 0 1,0 4.4,0 a2.2,2.2 0 1,0 -4.4,0 M73.5,49 m-2.2,0 a2.2,2.2 0 1,0 4.4,0 a2.2,2.2 0 1,0 -4.4,0"

BG=["#8B5CF6","#3B1A96"]
DROPC=["#FDE68A","#F59E0B"]
POCKETC="#FFFFFF"
RIMC="#EDE9FE"
STITCHC="#7C3AED"

svg=f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" width="96" height="96">
  <title>PocketDrop</title>
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="96" y2="96" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{BG[0]}"/><stop offset="1" stop-color="{BG[1]}"/>
    </linearGradient>
    <linearGradient id="drop" x1="40" y1="14" x2="58" y2="55" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{DROPC[0]}"/><stop offset="1" stop-color="{DROPC[1]}"/>
    </linearGradient>
  </defs>
  <rect width="96" height="96" rx="24" fill="url(#bg)"/>
  <path d="{" ".join(SPARKS)}" fill="#FFFFFF" fill-opacity="0.9"/>
  <path d="{DROP}" fill="url(#drop)"/>
  <path d="{DROP_SHINE}" fill="none" stroke="#FFFFFF" stroke-opacity="0.75" stroke-width="2.6" stroke-linecap="round"/>
  <path d="{MOTION}" fill="none" stroke="#FFFFFF" stroke-opacity="0.55" stroke-width="2.6" stroke-linecap="round"/>
  <path d="{POCKET}" fill="{POCKETC}"/>
  <path d="{RIM}" fill="{RIMC}"/>
  <path d="{STITCH}" fill="none" stroke="{STITCHC}" stroke-width="1.6" stroke-linecap="round"/>
  <path d="{RIVETS}" fill="url(#drop)"/>
</svg>
'''
open("pocketdrop.svg","w").write(svg)

def vd(size_dp, vp, group_open, group_close, with_bg, mono=False):
    grad=lambda c,x1,y1,x2,y2: f'''
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}">
                <item android:offset="0" android:color="{c[0]}" />
                <item android:offset="1" android:color="{c[1]}" />
            </gradient>
        </aapt:attr>'''
    out=[f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated with art/pocketdrop.svg: a loot drop falling into a stitched pocket. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="{vp}"
    android:viewportHeight="{vp}">''']
    out.append(group_open)
    if with_bg:
        out.append(f'''    <path android:pathData="M24,0 H72 A24,24 0 0,1 96,24 V72 A24,24 0 0,1 72,96 H24 A24,24 0 0,1 0,72 V24 A24,24 0 0,1 24,0 Z">{grad(BG,0,0,96,96)}
    </path>''')
    if mono:
        W="#FFFFFFFF"
        out.append(f'    <path android:fillColor="{W}" android:pathData="{" ".join(SPARKS)}" />')
        out.append(f'    <path android:fillColor="{W}" android:pathData="{DROP}" />')
        # Gap between drop and pocket so the shapes read apart in one colour.
        out.append(f'    <path android:strokeColor="{W}" android:strokeWidth="2.6" android:strokeLineCap="round" android:pathData="{MOTION}" />')
        out.append(f'    <path android:fillColor="{W}" android:pathData="{POCKET}" />')
    else:
        out.append(f'    <path android:fillColor="#E6FFFFFF" android:pathData="{" ".join(SPARKS)}" />')
        out.append(f'    <path android:pathData="{DROP}">{grad(DROPC,40,14,58,55)}\n    </path>')
        out.append(f'    <path android:strokeColor="#BFFFFFFF" android:strokeWidth="2.6" android:strokeLineCap="round" android:pathData="{DROP_SHINE}" />')
        out.append(f'    <path android:strokeColor="#8CFFFFFF" android:strokeWidth="2.6" android:strokeLineCap="round" android:pathData="{MOTION}" />')
        out.append(f'    <path android:fillColor="#FF{POCKETC[1:]}" android:pathData="{POCKET}" />')
        out.append(f'    <path android:fillColor="#FF{RIMC[1:]}" android:pathData="{RIM}" />')
        out.append(f'    <path android:strokeColor="#FF{STITCHC[1:]}" android:strokeWidth="1.6" android:strokeLineCap="round" android:pathData="{STITCH}" />')
        out.append(f'    <path android:pathData="{RIVETS}">{grad(DROPC,40,14,58,55)}\n    </path>')
    out.append(group_close)
    out.append('</vector>\n')
    return "\n".join(x for x in out if x)

# In-app logo: full icon incl. background.
open("ic_logo.xml","w").write(vd(96,96,"","",True))
# Launcher foreground: 96 art scaled into the 108 canvas; art spans ~66dp inside the 72dp safe zone.
g_open='    <group android:translateX="18" android:translateY="16" android:scaleX="0.75" android:scaleY="0.75">'
open("ic_launcher_foreground.xml","w").write(vd(108,108,g_open,"    </group>",False))
open("ic_launcher_monochrome.xml","w").write(vd(108,108,g_open,"    </group>",False,mono=True))
print(len(d),"stitches")
