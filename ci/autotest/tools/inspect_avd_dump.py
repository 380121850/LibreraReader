# -*- coding: utf-8 -*-
import io
import re

for p in [r"results/20260906-125928_L0_ui-avd/evidence/emulator-5554/SM-02/cold_start_stuck.xml",
          r"results/20260906-125928_L0_ui-avd/evidence/emulator-5554/SM-03/tabs_dump.xml"]:
    s = io.open(p, encoding="utf-8").read()
    ts = [t for t in re.findall(r'text="([^"]{1,40})"', s) if t.strip()]
    ds = [t for t in re.findall(r'content-desc="([^"]{1,40})"', s) if t.strip()
          and "系统通知" not in t and "信号" not in t and "充电" not in t and "WLAN" not in t]
    pk = sorted(set(re.findall(r'package="(com\.[\w.]+)"', s)))
    print("==", p.split("/")[-1])
    print(" pkgs:", pk)
    print(" texts:", list(dict.fromkeys(ts))[:25])
    print(" descs:", list(dict.fromkeys(ds))[:15])
