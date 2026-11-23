from PIL import Image, ImageDraw
from pathlib import Path
import numpy as np
root=Path(__file__).resolve().parents[1]
source=root/'docs/maquette-reference.png'
im=Image.open(source).convert('RGB').resize((1648,928),Image.Resampling.LANCZOS)
original=im.copy()
assets=root/'app/src/main/assets/ui'
# Keep the approved image's border lighting, dock, background and fixed icons.
# Remove only live data and the fictitious navigation image.
def gradient_box(box, top, bottom, radius=0):
    global im
    x0,y0,x1,y1=box; w,h=x1-x0,y1-y0
    ys=np.linspace(0,1,h)[:,None,None]
    a=np.array(top)[None,None,:]*(1-ys)+np.array(bottom)[None,None,:]*ys
    a=np.tile(a,(1,w,1)).astype('uint8')
    patch=Image.fromarray(a)
    if radius:
        mask=Image.new('L',(w,h));ImageDraw.Draw(mask).rounded_rectangle((0,0,w,h),radius,fill=255)
        im.paste(patch,(x0,y0),mask)
    else: im.paste(patch,(x0,y0))
# Preserve the existing soft texture around the clock with per-row interpolation.
a=np.array(im)
for y in range(0,103):
    for x in range(690,967):
        f=(x-690)/277
        a[y,x]=(a[y,687].astype(float)*(1-f)+a[y,970].astype(float)*f).astype('uint8')
im=Image.fromarray(a)
gradient_box((33,260,615,730),(9,27,43),(12,22,37),38)
gradient_box((1178,158,1600,363),(15,25,42),(13,23,38),15)
gradient_box((918,482,1595,581),(17,18,34),(17,18,33),5)
gradient_box((920,585,1583,618),(18,18,35),(18,18,35),3)
gradient_box((1154,617,1267,724),(18,18,35),(21,20,40),48)
gradient_box((1516,482,1572,537),(17,18,34),(17,18,33),5)
gradient_box((869,414,1404,476),(19,20,36),(19,20,36),29)
im.save(assets/'shell.webp',quality=96,method=6)
original.crop((691,470,879,665)).save(assets/'cover.webp',quality=96)
original.crop((81,161,157,239)).save(assets/'waze.webp',quality=96)
print(im.size,assets/'shell.webp')
