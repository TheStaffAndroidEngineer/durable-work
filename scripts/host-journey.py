#!/usr/bin/env python3
"""Destructive only to this lab's app data and synthetic fixture. Run on the named lab emulator."""
from pathlib import Path
from fresh_android_xml import read_ui_xml
from common_goods_ui import CommonGoodsUi
from verified_note_input import enter_note
import signal
import datetime, hashlib, json, os, re, shutil, subprocess, time, urllib.request, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ.get('CHAPTER14_EVIDENCE', ROOT/'evidence/host-journeys'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
OUT.mkdir(parents=True,exist_ok=False)
for helper_name in ('host-journey.py', 'common_goods_ui.py', 'fresh_android_xml.py', 'verified_note_input.py'):
    helper = Path(__file__).with_name(helper_name)
    if helper.is_file():
        (OUT / helper_name).write_bytes(helper.read_bytes())
ADB=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
JAVA=Path(os.environ['JAVA_HOME'])/'bin/java'
APP='staff.android.durable'
backend=None; logs=[]; events=[]; server_number=0
apk=OUT/'tested-app-debug.apk'
server_jar=OUT/'tested-server.jar'
shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk',apk)
shutil.copytree(ROOT/'server/build/install/server/lib',OUT/'tested-server-libs')
shutil.copy2(OUT/'tested-server-libs/server.jar',server_jar)
artifact_record={'tested_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'tested_server_jar_sha256':hashlib.sha256(server_jar.read_bytes()).hexdigest(),
    'server_distribution_jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted((OUT/'tested-server-libs').glob('*.jar'))}}
(OUT/'artifacts.json').write_text(json.dumps(artifact_record,indent=2)+'\n')

def run(*args):
    p=subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True,check=True)
    return p.stdout

def xml(allow_system=False):
    expected = {APP}
    if allow_system:
        expected |= {'com.android.systemui', 'com.android.permissioncontroller', 'com.google.android.permissioncontroller'}
    return read_ui_xml(run, '/sdcard/chapter14-window.xml', expected)

ui = CommonGoodsUi(xml, run, OUT)
def capture(name, contains=(), absent=()):
    frames=ui.observe(name)
    content='\n'.join(value for _,value in frames)
    for text in contains: assert text in content, (name,'missing',text)
    for text in absent: assert text not in content, (name,'unexpected',text)
    events.append({'screen':name,'viewports':[label for label,_ in frames],
                   'asserted':list(contains),'absent':list(absent),
                   'scope':'Assertions span retained scrolling viewports; not one simultaneous screenshot'})

def tap(text):
    original = ui.read_xml
    try:
        if text == 'Allow':
            ui.read_xml = lambda: xml(allow_system=True)
        ui.tap(text)
    finally:
        ui.read_xml = original

def edit(text):
    number=1+sum('inputSetup' in event for event in events)
    events.append(enter_note(ui,run,xml,OUT,f'input-{number:02}',text))

def control(name):
    req=urllib.request.Request('http://127.0.0.1:8789/control/'+name,data=b'',method='POST')
    with urllib.request.urlopen(req,timeout=5) as response: assert response.status==200

def start():
    global backend,server_number
    server_number+=1
    f=(OUT/f'server-{server_number}.log').open('w');logs.append(f)
    backend=subprocess.Popen([str(JAVA),'-cp',str(OUT/'tested-server-libs/*'),
        'staff.android.durable.server.ServerMainKt'],stdout=f,stderr=subprocess.STDOUT)
    for _ in range(100):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8789/control/journal',timeout=.2): return
        except Exception: time.sleep(.1)
    raise RuntimeError('Server did not start')

def stop():
    global backend
    if backend is not None:
        backend.send_signal(signal.SIGCONT); backend.terminate(); backend.wait(timeout=10);backend=None

def launch():
    run('shell','am','start','-n',APP+'/.MainActivity');time.sleep(1)

def journal():
    with urllib.request.urlopen('http://127.0.0.1:8789/control/journal') as response:
        return json.loads(response.read())

def top():
    ui.top()

try:
    start()
    run('reverse','tcp:8789','tcp:8789')
    run('install','-r',str(apk))
    installed=run('shell','pm','path',APP).decode().strip().removeprefix('package:')
    assert installed.startswith('/') and '\n' not in installed
    installed_hash=run('shell','sha256sum',installed).decode().split()[0]
    assert installed_hash==artifact_record['tested_apk_sha256']
    artifact_record.update(installed_path=installed,installed_sha256=installed_hash)
    (OUT/'artifacts.json').write_text(json.dumps(artifact_record,indent=2)+'\n')
    run('shell','pm','clear',APP)
    launch();tap('Account A');tap('Refresh');time.sleep(.5)
    capture('01-base',('Synced','Compare lenses','Server revision 5'))
    run('shell','svc','wifi','disable');run('shell','svc','data','disable');time.sleep(2)
    edit('Check lens mount');tap('Save and schedule')
    capture('02-pending-without-network',('Saved note: Check lens mount','Pending'))
    (OUT/'jobs-before-death.txt').write_bytes(run('shell','dumpsys','jobscheduler'))
    old_pid=run('shell','pidof',APP).decode().strip()
    run('shell','input','keyevent','3')
    run('shell','run-as',APP,'kill','-9',old_pid)
    time.sleep(.5)
    stopped=run('shell','dumpsys','package',APP).decode()
    assert 'stopped=false' in stopped
    (OUT/'package-after-abrupt-death.txt').write_text(stopped)
    run('shell','svc','wifi','enable');run('shell','svc','data','enable')
    began=time.monotonic()
    for _ in range(120):
        if any('PUT A' in row and '200 revision=6' in row for row in journal()): break
        time.sleep(1)
    else: raise AssertionError('No observed background acceptance within host observation window')
    new_pid=run('shell','pidof',APP).decode().strip()
    assert new_pid and old_pid!=new_pid
    (OUT/'activity-before-reopen.txt').write_bytes(run('shell','dumpsys','activity','activities'))
    events.append({'abruptDebugProcessDeath':{'oldPid':old_pid,'newPid':new_pid,
        'observationSeconds':round(time.monotonic()-began,2),'activityWasNotReopened':True}})
    launch()
    capture('03-background-result-reopened',('Saved note: Check lens mount','Synced','Server revision 6'))
    control('expire');edit('Ask about warranty');tap('Save and schedule');time.sleep(2)
    capture('04-expired-retains-note',('Saved note: Ask about warranty','Session expired','Sign in again'))
    control('renew');tap('Sign in again');time.sleep(2)
    capture('05-renewed-session-drained',('Saved note: Ask about warranty','Synced','Server revision 7'))
    tap('Download catalog');time.sleep(3)
    capture('06-uidt-complete-notifications-denied',('Catalog: Complete','49152 bytes','Drawer notifications are off'))
    (OUT/'uidt-jobs.txt').write_bytes(run('shell','dumpsys','jobscheduler'))
    tap('Enable notifications');tap('Allow')
    capture('permission-granted',(),('Drawer notifications are off',))
    tap('Download catalog')
    tap('Cancel download')
    time.sleep(.5)
    capture('07-catalog-cancelled',('Catalog: Cancelled',))
    time.sleep(3)
    capture('08-late-body-still-cancelled',('Catalog: Cancelled',))
    tap('Download catalog')
    backend.send_signal(signal.SIGSTOP) # Keep the bounded response incomplete while exposing its action.
    run('shell','cmd','statusbar','expand-notifications')
    content=xml(allow_system=True);tree=ET.fromstring(content)
    if not any(n.get('text','').lower()=='cancel' for n in tree.iter('node')):
        expand=next(n for n in tree.iter('node') if n.get('content-desc')=='Expand')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',expand.get('bounds')))
        run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        content=xml(allow_system=True);tree=ET.fromstring(content)
    (OUT/'notification-action.xml').write_text(content)
    (OUT/'notification-action.png').write_bytes(run('exec-out','screencap','-p'))
    node=next(n for n in tree.iter('node') if n.get('text','').lower()=='cancel')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    run('shell','cmd','statusbar','collapse');backend.send_signal(signal.SIGCONT);time.sleep(.5)
    capture('09-notification-cancelled',('Catalog: Cancelled',))
    tap('Sign out · discard local edits');tap('Account B');tap('Refresh');time.sleep(.5)
    top()
    if ui.text('Saved note: Account B private note') is None:
        capture('first-account-b-read-unavailable',('Action unavailable',))
        events.append({'explicitReadRetry':'First post-pause Refresh was unavailable; retry from the UI'})
        tap('Refresh');time.sleep(.8);top()
    capture('10-account-b',('Account B','Saved note: Account B private note'),('Ask about warranty',))
    (OUT/'wire-journal.json').write_text(json.dumps(journal(),indent=2)+'\n')
    (OUT/'logcat.txt').write_bytes(run('logcat','-d','-t','1200'))
    result={'status':'PASS','events':events,'tested_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
        'tested_server_jar_sha256':hashlib.sha256(server_jar.read_bytes()).hexdigest(),
        'device':run('shell','getprop','ro.build.fingerprint').decode().strip(),
        'boundaries':['Debug run-as SIGKILL simulates abrupt process death, not measured OS low-memory behavior',
        'No force-stop used for the background-recreation case; package remained stopped=false',
        'Observed scheduler delay is one emulator functional observation, never a delivery guarantee',
        'Notification Cancel checked with host fixture briefly SIGSTOP-paused during its incomplete bounded response',
        'API34+ UIDT ran on API36; API26–33 foreground fallback not device-executed here']}
    (OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
except Exception as failure:
    (OUT/'failure.json').write_text(json.dumps({'status':'FAIL','error':repr(failure),'events':events,**artifact_record},indent=2)+'\n')
    raise
finally:
    if backend is not None:
        backend.send_signal(signal.SIGCONT)
        try: (OUT/'wire-journal-finally.json').write_text(json.dumps(journal(),indent=2)+'\n')
        except Exception: pass
    run('shell','svc','wifi','enable');run('shell','svc','data','enable')
    stop()
    for f in logs:f.close()
