"""Prepare a binary F-Droid repository in an existing private fdroid init directory.

Run with the pinned WSL virtualenv. Never deploy the working directory itself:
only its repo/ subtree is public. config.yml and keystore.p12 are private.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

import qrcode
import yaml
from PIL import Image

CERT = '570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0'
URL = 'https://94043.net/fdroid/repo/'

p = argparse.ArgumentParser()
p.add_argument('--project', type=Path, required=True)
p.add_argument('--work', type=Path, required=True)
args = p.parse_args()
root, work = args.project.resolve(), args.work.resolve()
config_path = work / 'config.yml'
config = yaml.safe_load(config_path.read_text())
assert (work / config['keystore']).is_file(), 'Existing repository key required'
config.update(repo_url=URL.rstrip('/'), repo_name='Vikify by VictoriaV',
    repo_description='Official publisher repository for Vikify. Android VPN client for user-provided VLESS servers. Not the F-Droid main catalog.',
    repo_icon='vikify-icon.png', archive_older=0, make_current_version_link=False,
    update_stats=False, allow_disabled_algorithms=False, categories=['Internet'])
config_path.write_text(yaml.safe_dump(config, sort_keys=False), encoding='utf-8')
config_path.chmod(0o600)
(work / config['keystore']).chmod(0o600)
release = root / 'release-artifacts/release-1.6.0-23'
apk = release / 'Vikify-1.6.0-build23-arm64-release.apk'
validation = json.loads((root / 'app/build/reports/release23/release-validation.json').read_text())
assert validation['cert'] == CERT
assert hashlib.sha256(apk.read_bytes()).hexdigest() == validation['apk']
(work / 'repo').mkdir(exist_ok=True)
shutil.copy2(apk, work / 'repo' / apk.name)
with Image.open(root / 'app/src/main/res/drawable/ic_launcher_colibri.jpg') as icon:
    icon.convert('RGB').resize((512, 512)).save(work / 'vikify-icon.png')
metadata = dict(Categories=['Internet'], License='MIT', AuthorName='VictoriaV',
    WebSite='https://94043.net/vikify/',
    SourceCode='https://94043.net/vikify/Vikify-1.6.0-build23-source.zip',
    IssueTracker='https://github.com/GradeApp/Vikify/issues',
    Name='Vikify', Summary='VLESS VPN client for your own servers',
    Description='<p>Import a VLESS subscription using pasted text, a camera QR scan or a QR image. Supports TCP REALITY, XHTTP TLS and WebSocket TLS.</p><p>VPN server access is supplied by you or your provider. No advertising, analytics or account registration. Android 8.0 or newer, ARM64 devices only.</p><p>Published and signed by VictoriaV. Bundled native components retain their MIT and MPL-2.0 licenses. This is an independent publisher repository; this build has not been reviewed by the F-Droid main catalog.</p>',
    AutoUpdateMode='None', UpdateCheckMode='None',
    CurrentVersion='1.6.0', CurrentVersionCode=23, AllowedAPKSigningKeys=[CERT])
(work / 'metadata').mkdir(exist_ok=True)
(work / 'metadata/vikify.vpn.yml').write_text(yaml.safe_dump(metadata, sort_keys=False), encoding='utf-8')
for locale, description, notes in [
    ('en-US', 'VLESS VPN client for your own servers', 'F-Droid distribution build. Same features as build 22; existing subscriptions and profile selection are retained when updating.'),
    ('ru-RU', 'VPN-клиент для ваших VLESS-серверов', 'Сборка для распространения через F-Droid. Функции build 22 сохранены. Обновление сохраняет подписку и выбранный профиль.')]:
    loc = work / 'metadata/vikify.vpn' / locale
    (loc / 'changelogs').mkdir(parents=True, exist_ok=True)
    (loc / 'title.txt').write_text('Vikify', encoding='utf-8')
    (loc / 'short_description.txt').write_text(description, encoding='utf-8')
    (loc / 'full_description.txt').write_text(metadata['Description'] if locale == 'en-US' else
        '<p>Добавьте свою VLESS-подписку из буфера обмена, QR-кода камеры или изображения. Поддерживаются TCP REALITY, XHTTP TLS и WebSocket TLS.</p><p>Доступ к серверу предоставляется вашим провайдером или настраивается самостоятельно. Без рекламы, аналитики и регистрации. Android 8.0+, только ARM64.</p><p>Издатель: VictoriaV. Собственный репозиторий издателя; сборка не проходила проверку основного каталога F-Droid.</p>', encoding='utf-8')
    (loc / 'images').mkdir(exist_ok=True)
    shutil.copy2(work / 'vikify-icon.png', loc / 'images/icon.png')
    (loc / 'changelogs/23.txt').write_text(notes, encoding='utf-8')
env = dict(os.environ, VIKIFY_FDROID_KEY_PASSWORD=config['keystorepass'])
der = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(work / config['keystore']),
    '-alias', config['repo_keyalias'], '-storepass:env', 'VIKIFY_FDROID_KEY_PASSWORD'], env=env)
fingerprint = hashlib.sha256(der).hexdigest().upper()
https_url = URL + '?fingerprint=' + fingerprint
add_url = https_url.replace('https://', 'fdroidrepos://', 1)
public = root / 'release-artifacts/fdroid-20261107'
public.mkdir(exist_ok=True)
(public / 'repository-certificate.der').write_bytes(der)
(public / 'repository.json').write_text(json.dumps(dict(url=URL, addUrl=add_url, httpsUrl=https_url, fingerprint=fingerprint,
    apkSigningCertificate=CERT, version='1.6.0', versionCode=23), indent=2) + '\n')
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
qr.add_data(https_url)
qr.make(fit=True)
qr.make_image(fill_color='black', back_color='white').save(public / 'vikify-fdroid-qr.png')
print(json.dumps(dict(url=URL, fingerprint=fingerprint, versionCode=23)))
