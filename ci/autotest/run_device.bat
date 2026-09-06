@echo off
cd /d Z:\opt\librera\LibreraReader\ci\autotest
echo ===== DEVICE L0 =====
python run_all.py --level L0
echo ===== DEVICE L1 =====
python run_all.py --level L1
echo ===== DEVICE ALL DONE =====
