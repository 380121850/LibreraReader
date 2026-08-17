#!/usr/bin/env python3
"""Generate a minimal one-page PDF for NAPI render verification."""
import os

objs = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
    b"/Resources << /Font << /F1 5 0 R >> >> >>",
    None,  # content stream, filled below
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
]
stream = b"BT /F1 36 Tf 72 700 Td (Hello Librera HarmonyOS) Tj ET\n" \
         b"BT /F1 18 Tf 72 660 Td (MuPDF NAPI render test) Tj ET"
objs[3] = b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"

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
print("PDF written:", os.path.abspath(target), len(out), "bytes")
