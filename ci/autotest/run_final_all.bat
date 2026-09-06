@echo off
rem HowRead auto test full regression (device layer)
cd /d Z:\opt\librera\LibreraReader\ci\autotest
echo ===== FINAL L0 x3 =====
python run_all.py --level L0
echo ===== FINAL L1 x3 =====
python run_all.py --level L1
echo ===== FINAL L2 MI9 =====
python run_all.py --level L2 --serial 48fee174
echo ===== ALL DONE =====
