"""Export only signed public repository files; never the working directory."""
from pathlib import Path
import hashlib
import html
import json
import os
import shutil
import subprocess
import tarfile
import zipfile
import yaml

root = Path('/mnt/c/Users/adesp/Desktop/Cursor/Vikify')
work = Path.home() / 'vikify-fdroid/work'
out = root / 'release-artifacts/fdroid-20261107'
info = json.loads((out/'repository.json').read_text())
repo = work/'repo'
# fdroidserver signs legacy indexes with SHA-1 for Android <4.3. Vikify
# requires Android 8+, so use its supported modern signer for all indexes.
config = yaml.safe_load((work/'config.yml').read_text())
env = dict(os.environ, FDROID_KEY_STORE_PASS=config['keystorepass'], FDROID_KEY_PASS=config['keypass'])
for name in ['index-v1.jar', 'index.jar']:
    subprocess.run(['/usr/bin/apksigner', 'sign', '--min-sdk-version', '23', '--max-sdk-version', '24',
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'false',
        '--v4-signing-enabled', 'false', '--ks', str(work/config['keystore']),
        '--ks-key-alias', config['repo_keyalias'], '--ks-pass', 'env:FDROID_KEY_STORE_PASS',
        '--key-pass', 'env:FDROID_KEY_PASS', str(repo/name)], env=env, check=True, capture_output=True)
# Validate each signed index with its actual embedded certificate.
for name in ['entry.jar', 'index-v1.jar', 'index.jar']:
    result = subprocess.run(['jarsigner','-verify',str(repo/name)], capture_output=True, text=True, check=True)
    assert 'jar verified' in result.stdout.lower(), (name, result.stdout, result.stderr)
    with zipfile.ZipFile(repo/name) as z:
        signatures = [n for n in z.namelist() if n.startswith('META-INF/') and n.endswith('.RSA')]
        assert len(signatures) == 1
        from cryptography.hazmat.primitives.serialization.pkcs7 import load_der_pkcs7_certificates
        from cryptography.hazmat.primitives import serialization
        cert = load_der_pkcs7_certificates(z.read(signatures[0]))[0]
        assert hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest().upper() == info['fingerprint']
index = json.loads((repo/'index-v2.json').read_text())
assert list(index['packages']) == ['vikify.vpn']
version = next(iter(index['packages']['vikify.vpn']['versions'].values()))
assert version['manifest']['versionCode'] == 23
assert version['manifest']['signer']['sha256'] == [info['apkSigningCertificate']]
staging = out/'public'
(staging/'repo').mkdir(parents=True, exist_ok=True)
allowed = {'.apk','.jar','.json','.xml','.png','.css','.html'}
for f in repo.rglob('*'):
    if not f.is_file() or 'status' in f.relative_to(repo).parts:
        continue
    assert f.suffix in allowed, f.name
    assert 'keystore' not in f.name and 'config' not in f.name
    dst = staging/'repo'/f.relative_to(repo)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(f,dst)
for name in ['vikify-fdroid-qr.png','repository-certificate.der','repository.json']:
    shutil.copy2(out/name, staging/name)
page = '''<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Vikify — F-Droid</title><style>body{font:17px/1.6 system-ui,sans-serif;background:#111b16;color:#f2f1df;margin:0}main{max-width:760px;margin:auto;padding:32px 22px}a{color:#d9dfab}.button{display:inline-block;background:#d8dda5;color:#14251a;padding:12px 20px;border-radius:12px;text-decoration:none;font-weight:700}img{width:min(100%,360px);height:auto;border-radius:12px}code{overflow-wrap:anywhere;font-size:13px}section{border-top:1px solid #ffffff30;padding-top:24px;margin-top:32px}h1{line-height:1.2}li{margin-bottom:10px}</style></head><body><main><p>VICTORIAV · ANDROID</p><h1>Vikify в F-Droid</h1><p>Собственный репозиторий издателя. Android 8.0+ · ARM64. Vikify 1.6.0, build 23.</p><p><a class="button" href="ADD_URL">Добавить в F-Droid / Add repository</a></p><p><img src="vikify-fdroid-qr.png" width="488" height="488" alt="QR-код добавления репозитория Vikify с отпечатком сертификата"></p><ol><li>Установите <a href="https://f-droid.org/">официальный клиент F-Droid</a>.</li><li>Откройте ссылку выше на телефоне или отсканируйте QR-код через добавление репозитория в F-Droid.</li><li>Подтвердите добавление «Vikify by VictoriaV», дождитесь обновления каталога и выберите Vikify.</li><li>Установите приложение или обновите существующий релиз. Совпадающая подпись сохраняет данные; удалять Vikify не нужно.</li></ol><p>Адрес: <a href="repo/">https://94043.net/fdroid/repo/</a><br>SHA-256 сертификата репозитория:<br><code>FINGERPRINT</code></p><p>Это отдельный репозиторий VictoriaV. Приложение здесь не проходило проверку основного каталога F-Droid. Доступ к VPN-серверу предоставляется отдельно.</p><section lang="en"><h2>Install and update with F-Droid</h2><p>Install the official F-Droid client, open the Add repository link or scan the QR code, then confirm “Vikify by VictoriaV”. Refresh the catalog and install or update Vikify. Existing matching signed installations keep their subscription and profile.</p><p>This is the publisher’s independent repository, not the F-Droid main catalog. Android 8.0 or newer; ARM64 only. Supply your own VLESS server or subscription.</p></section><p><a href="/vikify/">APK / application website</a> · <a href="/vikify/privacy.html">Privacy</a> · <a href="https://github.com/GradeApp/Vikify">GitHub</a></p></main></body></html>'''
(staging/'index.html').write_text(page.replace('ADD_URL',html.escape(info['addUrl'],quote=True)).replace('FINGERPRINT',info['fingerprint']),encoding='utf-8')
files = {p.relative_to(staging).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in staging.rglob('*') if p.is_file()}
(out/'public-files-sha256.json').write_text(json.dumps(files,indent=2)+'\n')
with tarfile.open(out/'fdroid-public.tar','w') as tar:
    for name in files:
        tar.add(staging/name, arcname=name)
print(f'Checked three signed indexes, APK identity and {len(files)} public files; prepared fdroid-public.tar')
