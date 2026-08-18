#!/usr/bin/env python3
"""Generate a multi-page PDF for NAPI render / paging verification."""
import os

PAGES = 5
WIDTH, HEIGHT = 612, 792

page_objs = []
for i in range(1, PAGES + 1):
    stream = (b"BT /F1 36 Tf 72 700 Td (" +
              f"Page {i}".encode() +
              b") Tj ET\n"
              b"BT /F1 18 Tf 72 660 Td (MuPDF NAPI paging test) Tj ET")
    page_objs.append(
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " +
        str(WIDTH).encode() + b" " + str(HEIGHT).encode() +
        b"] /Contents " + str(3 + i * 2).encode() + b" 0 R "
        b"/Resources << /Font << /F1 " + str(3 + PAGES * 2 + 1).encode() +
        b" 0 R >> >> >>")
    page_objs.append(b"<< /Length " + str(len(stream)).encode() +
                     b" >>\nstream\n" + stream + b"\nendstream")

kids = b" ".join(str(3 + i * 2).encode() + b" 0 R" for i in range(PAGES))
objs = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [" + kids + b"] /Count " + str(PAGES).encode() + b" >>",
] + page_objs + [b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]

out = b"%PDF-1.4\n"
offsets = []
for i, o in enumerate(objs, 1):
    offsets.append(len(out))
    out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
xref_pos = len(out)
out += b"xref\n0 " + str(len(objs) + 1).encode() + b"\n0000000000 65535 f \n"
for off in offsets:
    out += f"{off:010d} 00000 n \n".encode()
out += (b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\n"
        b"startxref\n" + str(xref_pos).encode() + b"\n%%EOF")

target = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "entry", "src", "main", "resources", "rawfile", "test.pdf")
os.makedirs(os.path.dirname(target), exist_ok=True)
with open(target, "wb") as f:
    f.write(out)
print("PDF written:", os.path.abspath(target), len(out), "bytes,", PAGES, "pages")
