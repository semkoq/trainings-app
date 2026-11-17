# Passt das von Capacitor erzeugte Android-Projekt an (Rechte, Dienst, Deep-Link, Version, Icons).
import os, re, shutil, sys
run = int(os.environ.get('RUN', '1'))
A = 'android/app/src/main'
m = open(f'{A}/AndroidManifest.xml').read()
perms = ['android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation',
         'android.permission.BLUETOOTH_CONNECT',
         'android.permission.BLUETOOTH" android:maxSdkVersion="30',
         'android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30',
         'android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30',
         'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE',
         'android.permission.POST_NOTIFICATIONS', 'android.permission.WAKE_LOCK', 'android.permission.VIBRATE',
         'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS']
add = ''.join(f'    <uses-permission android:name="{p}" />\n' for p in perms)
add += '    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />\n'
m = m.replace('<application', add + '\n    <application', 1)
svc = '''        <service android:name=".TrainingService" android:exported="false" android:foregroundServiceType="connectedDevice" />
'''
m = m.replace('</application>', svc + '    </application>', 1)
deeplink = '''            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="trainingsapp" />
            </intent-filter>
'''
m = re.sub(r'(<activity[^>]*MainActivity[\s\S]*?</intent-filter>\n)', lambda x: x.group(1) + deeplink, m, count=1)
m = m.replace('android:launchMode="singleTask"', 'android:launchMode="singleTask" android:screenOrientation="portrait"')
open(f'{A}/AndroidManifest.xml', 'w').write(m)
assert 'TrainingService' in m and 'trainingsapp' in m and 'CONNECTED_DEVICE' in m, 'Manifest-Patch fehlgeschlagen'

g = open('android/app/build.gradle').read()
g = re.sub(r'versionCode \d+', f'versionCode {run}', g)
g = re.sub(r'versionName "[^"]*"', f'versionName "1.{run}"', g)
open('android/app/build.gradle', 'w').write(g)

j = f'{A}/java/de/semkoq/trainingsapp'
os.makedirs(j, exist_ok=True)
for f in os.listdir('java'):
    shutil.copy(f'java/{f}', f'{j}/{f}')
for d in os.listdir(f'{A}/res'):
    if d.startswith('mipmap'):
        shutil.rmtree(f'{A}/res/{d}')
for dens, px in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    os.makedirs(f'{A}/res/mipmap-{dens}', exist_ok=True)
    shutil.copy(f'icons/ic_launcher_{px}.png', f'{A}/res/mipmap-{dens}/ic_launcher.png')
    shutil.copy(f'icons/ic_launcher_{px}.png', f'{A}/res/mipmap-{dens}/ic_launcher_round.png')
print('Patch ok, versionCode', run)
