import subprocess, time, json, re, xml.etree.ElementTree as ET
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
WORK = BASE / '08_영상제작_작업파일'
RAW = BASE / '06_촬영한_원본영상'
ADB = r'C:\Users\smgjj\AppData\Local\Android\Sdk\platform-tools\adb.exe'
events = json.loads((WORK/'recording_events.json').read_text(encoding='utf-8')) if (WORK/'recording_events.json').exists() else {}

def adb(*args):
    return subprocess.run([ADB, '-s', 'emulator-5554', *args], capture_output=True, timeout=30, check=True)

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/film-ui.xml')
    adb('pull', '/sdcard/film-ui.xml', str(WORK / 'live-ui.xml'))
    return ET.parse(WORK / 'live-ui.xml').getroot()

def tap_text(text, contains=False, tree=None):
    tree = tree if tree is not None else ui()
    for n in tree.iter('node'):
        value = n.get('text', '')
        if (text in value if contains else value.startswith(text)):
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
    raise RuntimeError('Visible button not found: ' + text)

def image(name):
    adb('shell', 'screencap', '-p', '/sdcard/film.png')
    adb('pull', '/sdcard/film.png', str(WORK / (name+'.png')))

def open_app(package):
    adb('shell', 'am', 'start', '-n', package+'/com.chargecheck.app.MainActivity')
    time.sleep(3)

def record(name, seconds, scenario=None, speed=None, action=None):
    if speed:
        tap_text('×'+str(speed))
    before = ui() if scenario else None
    remote = '/sdcard/'+name+'.mp4'
    started = time.monotonic()
    proc = subprocess.Popen([ADB,'-s','emulator-5554','shell','screenrecord','--size','1920x1080','--bit-rate','8000000','--time-limit',str(seconds),remote],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    events[name] = {'duration_requested':seconds,'kind':'real release' if scenario is None else 'fake-data demo'}
    time.sleep(1)
    if scenario:
        tap_text(scenario,tree=before)
        events[name]['scenario_start'] = round(time.monotonic()-started,3)
    print('Recording: '+name,flush=True)
    if action:
        action(started, events[name])
    remaining = seconds-(time.monotonic()-started)
    if remaining>0: time.sleep(remaining)
    out,err=proc.communicate(timeout=15)
    if proc.returncode: raise RuntimeError(err.decode(errors='replace'))
    adb('pull',remote,str(RAW/(name+'.mp4')))
    events[name]['size_bytes']=(RAW/(name+'.mp4')).stat().st_size
    (WORK/'recording_events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Saved: '+name,flush=True)

def wait_at(started, seconds):
    remain = seconds-(time.monotonic()-started)
    if remain>0: time.sleep(remain)

def real_action(started, log):
    wait_at(started,4)
    tap_text('운영사 충전 중 확인')
    log['station_selected']=round(time.monotonic()-started,3)
    time.sleep(2)
    image('real_detail')
    tap_text('닫기')

def fail_action(started, log):
    wait_at(started,log['scenario_start']+40)
    # The visible left edge of Stop remains outside the question sheet.
    # Pause scenario playback before its scripted answer; the user's answer below is real.
    adb('shell','input','tap','930','1230')
    log['scenario_paused']=round(time.monotonic()-started,3)
    image('failure_question')
    tap_text('응, 안 돼요')
    log['manual_yes']=round(time.monotonic()-started,3)
    time.sleep(1.5)
    image('failure_recorded')

def warning_action(started, log):
    wait_at(started,log['scenario_start']+1)
    adb('shell','input','tap','930','1230')
    log['scenario_paused']=round(time.monotonic()-started,3)
    image('warning_before')
    # Warning layout was inspected in the recorded screenshot; pause neither the app nor its result.
    tree=ui()
    tap_text('대안으로 바꾸기',tree=tree)
    log['manual_switch']=round(time.monotonic()-started,3)
    time.sleep(1.5)
    image('warning_after')

if __name__=='__main__':
    import sys
    if '--remaining' not in sys.argv:
        open_app('com.chargecheck.app')
        record('01_real_첫화면과_상세선택',18,action=real_action)
        open_app('com.chargecheck.app.demo')
        record('02_demo_SOC상승_자동기록',39,'LIVE 정상',120)
    else:
        adb('shell','am','force-stop','com.chargecheck.app.demo')
        open_app('com.chargecheck.app.demo')
    record('03_demo_실패확인_직접응답',63,'LIVE 고장',30,fail_action)
    record('04_demo_경고와_대안변경',15,'도착 전 경고',30,warning_action)
    print('All recordings complete.',flush=True)
