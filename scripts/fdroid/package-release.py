"""Create the public build-23 files; no signing secrets are read or copied."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sqlite3

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('distribution', ROOT / 'scripts/prepare-distribution.py')
dist = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dist)
out = ROOT / 'release-artifacts/release-1.6.0-23'
info = json.loads((ROOT / 'release-artifacts/fdroid-20261107/repository.json').read_text())
validation = json.loads((ROOT / 'app/build/reports/release23/release-validation.json').read_text())
apk = 'Vikify-1.6.0-build23-arm64-release.apk'
source = 'Vikify-1.6.0-build23-source.zip'
with sqlite3.connect(Path('C:/Users/adesp/Desktop/Proxy/users.db').as_uri()+'?mode=ro', uri=True) as db:
    secrets = [str(v).encode() for row in db.execute('select token,uuid from users') for v in row if v and len(str(v)) > 12]
paths = [ROOT / name for name in ['.gitignore', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'keystore.properties.example', 'README.md', 'LICENSE', 'DISCLAIMER.md',
    'BUILD_AND_VALIDATE.md', 'app/build.gradle.kts', 'app/proguard-rules.pro', 'app/lint.xml',
    'vpnprobe/build.gradle.kts', 'scripts/build-signed-release.ps1', 'scripts/prepare-distribution.py']]
for folder in ['app/src', 'app/libs', 'gradle', 'vpnprobe/src', 'docs/legal', 'docs/user-guide', 'scripts/fdroid']:
    paths.extend(p for p in (ROOT / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for name in ['BUNDLED_THIRD_PARTY_NOTICES.md', 'RELEASE_SIGNING.md', 'PRIVACY_POLICY.md', 'DATA_INVENTORY.md', 'SUPPORTED_DEVICES_AND_ABI.md', 'FDROID_REPOSITORY.ru.md', 'RELEASE_1.6.0_BUILD23.ru.md']:
    paths.append(ROOT / 'docs' / name)
entries = {}
for path in paths:
    rel = path.relative_to(ROOT).as_posix()
    data = path.read_bytes()
    dist.scan(rel, data, secrets)
    entries[rel] = data
entries['SOURCES.sha256'] = ''.join(dist.sha(data)+'  '+name+'\n' for name, data in sorted(entries.items())).encode()
dist.archive(out / source, entries)
dist.write(out / (source+'.sha256'), dist.sha((out / source).read_bytes())+'  '+source+'\n')
for filename, origin in [('LICENSE', 'LICENSE'), ('android-notices.txt', 'docs/legal/android-notices.txt')]:
    shutil.copyfile(ROOT / origin, out / filename)
dist.write(out / 'RELEASE_NOTES.md', '''# Vikify 1.6.0 · build 23

VictoriaV · Android 8.0+ · ARM64 · target API 36

Сборка для собственного F-Droid-репозитория. По сравнению с build 22 изменён только versionCode приложения: функции и формат хранения данных сохранены. Устанавливайте поверх подписанного релиза; удаление приложения не требуется. Доступ к серверу не включён.

Distribution build for the publisher's F-Droid repository. Only the app versionCode changes from build 22; features and data storage remain unchanged. Install over a matching signed release to keep subscriptions. Server access is not included.

Repository: https://94043.net/fdroid/
This is an independent publisher repository, not the F-Droid main catalog.
''')
for lang, filename in [('ru', 'index.html'), ('en', 'index-en.html')]:
    html = dist.page(lang, '1.6.0', 23, apk, source, validation['apk'], (out / apk).stat().st_size / 1_000_000)
    label = 'Добавить репозиторий F-Droid' if lang == 'ru' else 'Add the F-Droid repository'
    addition = f'<p><a href="{info["addUrl"]}">{label}</a> · <a href="/fdroid/">QR / instructions</a></p>'
    html = html.replace('<div class="grid">', addition+'<div class="grid">', 1)
    html = html.replace('Обновления приложения устанавливаются вручную.', 'Обновления доступны через F-Droid или установкой APK поверх приложения.')
    html = html.replace('App updates are installed manually.', 'Update using F-Droid or install the APK over the existing app.')
    dist.write(out / filename, html)
public_names = [apk, apk+'.sha256', source, source+'.sha256', 'index.html', 'index-en.html', 'LICENSE',
    'android-notices.txt', 'native-notices.txt', 'privacy.html', 'privacy-en.html', 'RELEASE_NOTES.md', 'signing-certificate-sha256.txt']
manifest = dict(package='vikify.vpn', version='1.6.0', versionCode=23, publisher='VictoriaV',
    signingCertificateSha256=dist.CERT, repository=info['url'],
    assets=[dict(name=n, sha256=dist.sha((out / n).read_bytes()), bytes=(out/n).stat().st_size) for n in public_names])
dist.write(out / 'release-manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
dist.archive(ROOT / 'release-artifacts/fdroid-20261107/website-upload.zip', {n:(out/n).read_bytes() for n in public_names+['release-manifest.json']})
print('Prepared public APK, exact source snapshot, licenses, bilingual pages and manifest; private subscription scan passed.')
