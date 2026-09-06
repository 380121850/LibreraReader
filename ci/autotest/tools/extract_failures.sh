#!/usr/bin/env bash
# 从 gradle 测试结果 XML 提取失败用例与首行堆栈
cd /docker/opt/librera/LibreraReader/android/app/build/test-results/testGoogleDebugUnitTest || exit 1
python3 - <<'EOF'
import xml.etree.ElementTree as ET
import glob
for f in sorted(glob.glob('*.xml')):
    t = ET.parse(f)
    for tc in t.getroot().iter('testcase'):
        fails = list(tc.iter('failure')) + list(tc.iter('error'))
        for fail in fails:
            print('%s#%s' % (tc.get('classname'), tc.get('name')))
            txt = (fail.text or '').strip()
            lines = txt.splitlines() if txt else []
            first = lines[0] if lines else (fail.get('message') or '')[:200]
            print('   ->', first[:220])
            for ln in lines[1:7]:
                print('      ', ln.strip()[:160])
EOF
