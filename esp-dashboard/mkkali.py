from PIL import Image
W,H = 50,50
im = Image.open("kali_src.png").convert("RGBA")
a = im.split()[3]                       # alpha = dragon shape
bb = a.getbbox()
a = a.crop(bb)                          # trim transparent border
a = a.resize((W,H), Image.LANCZOS)
px = a.load()
on = [[1 if px[x,y] > 110 else 0 for x in range(W)] for y in range(H)]
# ascii preview
for y in range(0,H,2):
    print("".join("#" if on[y][x] else " " for x in range(0,W,1)))
# pack XBM (LSB-first, row byte-aligned)
rowbytes = (W+7)//8
data = []
for y in range(H):
    for b in range(rowbytes):
        byte = 0
        for bit in range(8):
            x = b*8+bit
            if x < W and on[y][x]:
                byte |= (1<<bit)
        data.append(byte)
with open("kali_logo.h","w") as f:
    f.write("// Kali dragon (Wikimedia Commons, Kali-dragon-icon.svg) -> 1-bit\n")
    f.write(f"#define KALI_W {W}\n#define KALI_H {H}\n")
    f.write("static const unsigned char kali_bits[] PROGMEM = {\n")
    for i in range(0,len(data),12):
        f.write("  "+", ".join("0x%02x"%b for b in data[i:i+12])+",\n")
    f.write("};\n")
print(f"\nbytes={len(data)}  ({W}x{H}, {rowbytes} B/row)")
