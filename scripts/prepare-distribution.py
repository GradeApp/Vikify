"""Prepare local website/GitHub kits. Does not upload or publish anything."""
from pathlib import Path
import argparse, hashlib, io, json, re, shutil, sqlite3, zipfile
from html import escape

ROOT = Path(__file__).resolve().parents[1]
REPO = 'GradeApp/Vikify'
CERT = '570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0'

def sha(data): return hashlib.sha256(data).hexdigest()
def write(path, text): path.write_text(text, encoding='utf-8', newline='\n')
def archive(path, entries):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 11, 5, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name == 'gradlew' else 0o100644) << 16
            z.writestr(info, data)
    with zipfile.ZipFile(path) as z: assert z.testzip() is None

def scan(name, data, secrets):
    if any(value in data for value in secrets): raise RuntimeError('Private subscription data found in '+name)
    if name.endswith(('.aar', '.jar', '.zip', '.apk', '.aab')):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for entry in z.infolist():
                if not entry.is_dir():
                    content = z.read(entry)
                    if any(value in content for value in secrets):
                        raise RuntimeError('Private subscription data inside '+name)

def page(lang, version, code, apk, source, apk_sha, size):
    ru = lang == 'ru'
    text = dict(
        title='Ваш VPN-клиент. Ваш сервер.' if ru else 'Your VPN client. Your server.',
        intro='Добавьте подписку своего провайдера, выберите профиль и подключитесь. Vikify не продаёт доступ к серверам.' if ru else 'Import your provider’s subscription, choose a profile and connect. Vikify does not sell server access.',
        download='Скачать APK для Android' if ru else 'Download APK for Android',
        meta='Android 8.0 и новее · ARM64 · RU / EN' if ru else 'Android 8.0 or newer · ARM64 · RU / EN',
        release='Версия' if ru else 'Version',
        install='Установка и обновление' if ru else 'Install and update',
        steps=['Скачайте APK на телефон. При необходимости разрешите установку для браузера или файлового менеджера в настройках Android.',
               'Откройте Vikify, прочитайте информацию о VPN и добавьте свою подписку: вставкой текста, QR-кодом камеры или из изображения.',
               'Выберите профиль, нажмите «Подключить» и подтвердите системный запрос VPN.',
               'Для обновления установите новый APK поверх приложения. Совпадающая подпись сохранит данные. Не удаляйте приложение перед обновлением.'] if ru else
              ['Download the APK on your phone. If prompted, allow installation from your browser or file manager in Android settings.',
               'Open Vikify, read the VPN disclosure and import your subscription by pasting text, scanning a QR code or choosing an image.',
               'Choose a profile, tap Connect and approve the Android VPN prompt.',
               'To update, install the new APK over the existing app. Matching signatures preserve your data. Do not uninstall first.'],
        formats='Что можно добавить' if ru else 'What you can import',
        supported='HTTPS-подписки VLESS, отдельные ссылки VLESS, обычные списки и Base64. Поддерживаются TCP REALITY, XHTTP TLS и WebSocket TLS. Другие протоколы, gRPC, конфигурации JSON и YAML не поддерживаются.' if ru else
                  'HTTPS VLESS subscriptions, individual VLESS links, plain lists and Base64. Supports TCP REALITY, XHTTP TLS and WebSocket TLS. Other protocols, gRPC, JSON and YAML configurations are not supported.',
        local='Один активный источник. Ссылка HTTPS обновляется в приложении; список из текста или QR без адреса обновления заменяется вручную. Зелёное имя означает доступность сервера, а не гарантию VPN-соединения.' if ru else
              'One active source. HTTPS subscriptions can refresh in the app; a text or QR list without an update address must be replaced manually. A green profile name indicates server reachability, not guaranteed VPN connectivity.',
        notes='Изменения версии' if ru else 'Release notes',
        privacy='Конфиденциальность' if ru else 'Privacy',
        private='Без рекламы и аналитики. Сведения о доступе хранятся на устройстве в зашифрованном виде. При подключении трафик проходит через выбранного вами VPN-провайдера; его правила конфиденциальности действуют отдельно.' if ru else
                'No ads or analytics. Access settings are stored encrypted on your device. While connected, traffic passes through your chosen VPN provider; its privacy practices apply separately.',
        policy='Политика конфиденциальности' if ru else 'Privacy policy',
        verify='Проверка файла' if ru else 'Verify your download',
        source='Исходники этой версии' if ru else 'Source snapshot',
        sign='Отпечаток сертификата подписи' if ru else 'Signing certificate fingerprint',
        footer='Клиент для личного использования. Доступ к VPN-серверу приобретается или настраивается отдельно. Обновления приложения устанавливаются вручную.' if ru else
               'A client for personal use. VPN server access is obtained or configured separately. App updates are installed manually.'
    )
    def t(key): return escape(text[key])
    steps=''.join('<li>'+escape(x)+'</li>' for x in text['steps'])
    return f'''<!doctype html>
<html lang="{lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="{t('intro')}"><meta name="color-scheme" content="dark"><title>Vikify — VictoriaV</title>
<style>
:root{{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#f2f1df;background:#111b16;line-height:1.6;font-synthesis:none}}
*{{box-sizing:border-box}}body{{margin:0;background:radial-gradient(ellipse at 85% 10%,#304b3655,transparent 55%);min-height:100vh}}a{{color:#d9dfab;text-underline-offset:4px}}a:hover{{color:#fff}}a:focus-visible{{outline:3px solid #ffe095;outline-offset:5px}}.wrap{{max-width:1000px;margin:auto;padding:24px}}header{{display:flex;justify-content:space-between;gap:20px;align-items:center}}.brand{{font-size:21px;letter-spacing:4px;font-weight:750}}.muted{{color:#bac7bb}}.hero{{padding:72px 0 44px;max-width:790px}}.eyebrow{{color:#d4c987;letter-spacing:2px;font-size:13px;text-transform:uppercase}}h1{{font-size:clamp(34px,6vw,60px);line-height:1.12;margin:14px 0 24px;letter-spacing:-1.5px}}h2{{font-size:21px;margin:0 0 14px}}p{{margin:0 0 17px}}.intro{{font-size:19px;max-width:650px;color:#ccd6cb}}.button{{display:inline-block;margin:14px 0 12px;background:#d8dda5;color:#14251a;text-decoration:none;font-weight:750;padding:15px 24px;border-radius:12px;box-shadow:0 6px 24px #0002}}.button:hover{{background:#ebefc0;color:#14251a}}.grid{{display:grid;grid-template-columns:1fr 1fr;gap:18px}}.card{{background:#c1d0a508;border:1px solid #c9d7b526;border-radius:20px;padding:26px}}.wide{{grid-column:1/-1}}ol{{padding-left:22px;margin:0}}li{{padding-left:5px;margin:0 0 14px}}li:last-child{{margin-bottom:0}}.links{{display:flex;flex-wrap:wrap;gap:10px 22px}}code{{font-size:12px;overflow-wrap:anywhere;word-break:break-all;color:#dce5c2}}footer{{padding:32px 0 14px;font-size:13px;color:#aebdad}}.size{{font-size:14px}}@media(max-width:640px){{.wrap{{padding:20px}}.hero{{padding-top:48px}}.grid{{grid-template-columns:1fr}}.card{{padding:21px}}.button{{display:block;text-align:center}}h1{{letter-spacing:-.8px}}}}
</style></head><body><div class="wrap"><header><span class="brand">VIKIFY</span><nav aria-label="Language"><a href="{'index-en.html' if ru else 'index.html'}" lang="{'en' if ru else 'ru'}">{'English' if ru else 'Русский'}</a></nav></header>
<main><section class="hero"><div class="eyebrow">VictoriaV · Android VPN</div><h1>{t('title')}</h1><p class="intro">{t('intro')}</p>
<a class="button" href="{apk}" download>{t('download')}</a><p class="muted size">{t('release')} {version} · build {code} · {size:.1f} MB<br>{t('meta')}</p></section>
<div class="grid"><section class="card wide"><h2>{t('install')}</h2><ol>{steps}</ol></section>
<section class="card"><h2>{t('formats')}</h2><p>{t('supported')}</p><p class="muted">{t('local')}</p></section>
<section class="card"><h2>{t('privacy')}</h2><p>{t('private')}</p><a href="{'privacy.html' if ru else 'privacy-en.html'}">{t('policy')}</a></section>
<section class="card wide"><h2>{t('verify')}</h2><p>SHA-256<br><code>{apk_sha}</code></p><div class="links"><a href="{apk}.sha256">SHA-256</a><a href="signing-certificate-sha256.txt">{t('sign')}</a><a href="{source}">{t('source')}</a><a href="RELEASE_NOTES.md">{t('notes')}</a><a href="https://github.com/{REPO}/releases">GitHub Releases</a></div></section></div></main>
<footer><p>{t('footer')}</p><div class="links"><span>© VictoriaV</span><a href="LICENSE">MIT</a><a href="android-notices.txt">Android licenses</a><a href="native-notices.txt">Native licenses</a></div></footer></div></body></html>'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--secrets-db', type=Path, help='Local operator DB, read-only; never copied or printed')
    args=parser.parse_args()
    config=(ROOT/'app/build.gradle.kts').read_text()
    version=re.search(r'versionName = "([\d.]+)"',config)[1]
    code=int(re.search(r'versionCode = (\d+)',config)[1])
    out=ROOT/f'release-artifacts/release-{version}-{code}'
    apk=f'Vikify-{version}-build{code}-arm64-release.apk'
    aab=f'Vikify-{version}-build{code}-release.aab'
    source=f'Vikify-{version}-build{code}-source.zip'
    checked=json.loads((ROOT/f'app/build/reports/release{code}/release-validation.json').read_text())
    assert checked['cert']==CERT and checked['version']==version and checked['versionCode']==code
    for name,kind in [(apk,'apk'),(aab,'aab')]: assert sha((out/name).read_bytes())==checked[kind]
    secrets=[]
    if args.secrets_db:
        with sqlite3.connect(args.secrets_db.resolve().as_uri()+'?mode=ro',uri=True) as db:
            for row in db.execute('select token,uuid from users'):
                secrets.extend(str(value).encode() for value in row if value and len(str(value))>12)
    shutil.copyfile(ROOT/'docs/legal/android-notices.txt',out/'android-notices.txt')
    shutil.copyfile(ROOT/'LICENSE',out/'LICENSE')
    notes=f'''# Vikify {version} · build {code}

Издатель / Publisher: VictoriaV. Android 8.0+ · ARM64 · target API 36.

## Русский

- Добавлены сторонние HTTPS-подписки VLESS, одиночные ссылки, обычные и Base64-списки.
- Вставка текста и QR из изображения используют общий безопасный импорт. Смешанный список показывает совместимые и пропущенные строки до сохранения.
- Отмена и ошибки сохраняют прежний доступ. Локальные списки не выполняют сетевое обновление; HTTPS-подписки обновляются по сохранённому адресу.
- Проверка сертификатов обязательна. Перенаправления только по HTTPS, до трёх переходов; размер ответа ограничен.
- Поддерживаются TCP REALITY, XHTTP TLS, WebSocket TLS. Другие протоколы/транспорты, JSON/YAML и несколько активных подписок не поддерживаются.
- Выбор профиля больше не ждёт завершения сетевого обновления подписки; запоздавший ответ не изменяет заменённый или удалённый доступ.
- Исправлены размеры диалогов и закрытие клавиатуры при крупном шрифте и горизонтальном экране.
- На Android 15–16 исправлена обработка системных отступов диалогов, включая доступность кнопки закрытия политики.
- Результаты и границы проверки приведены в `docs/RELEASE_{version}_BUILD{code}.ru.md` в архиве исходников.

Установка: скачайте APK и установите поверх предыдущего релиза. AAB предназначен для магазина и не устанавливается напрямую. Доступ к серверу в приложение не включён. Обновления APK выполняются вручную.

## English

- Import third-party HTTPS VLESS subscriptions, individual links, plain and Base64 lists.
- Pasted text and image QR codes share the same validated import flow. Review compatible and skipped entries before accepting a mixed list.
- Cancelled or failed imports keep existing access. Local sets have no network refresh; remote subscriptions retain their HTTPS update address.
- TLS certificate verification stays enabled. HTTPS-only redirects (up to three), response and input limits.
- Supported: TCP REALITY, XHTTP TLS and WebSocket TLS. Other protocols/transports, JSON/YAML and multiple active subscriptions are not supported.
- Profile selection no longer waits for a subscription download; stale responses cannot modify replaced or removed access.
- Fixed dialog sizing and keyboard dismissal with large fonts and landscape screens.
- Fixed dialog system insets on Android 15–16, including the privacy policy close button.
- Validation results and scope are documented in `docs/RELEASE_{version}_BUILD{code}.ru.md` in the source archive.

Install the APK over the previous signed release. AAB is for store processing, not direct installation. Server access is not bundled. APK updates are manual.

Independent provider credentials were not supplied; third-party format coverage uses renamed test profiles and the operator's working servers. The distributed APK supports ARM64. ARM32 and a physical 16 KB-page device are outside this validation scope.

Privacy: https://94043.net/vikify/privacy.html · English: https://94043.net/vikify/privacy-en.html
Signing certificate SHA-256: `{CERT}`
'''
    write(out/'RELEASE_NOTES.md',notes)
    # Only these build inputs and documentation may enter the source snapshot.
    root_files=['.gitignore','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat',
                'keystore.properties.example','README.md','LICENSE','DISCLAIMER.md','BUILD_AND_VALIDATE.md',
                'app/build.gradle.kts','app/proguard-rules.pro','app/lint.xml','vpnprobe/build.gradle.kts']
    selected_docs=['BUNDLED_THIRD_PARTY_NOTICES.md','RELEASE_SIGNING.md','SUPPORTED_DEVICES_AND_ABI.md',
                   'DISTRIBUTION.ru.md','PRIVACY_POLICY.md','DATA_INVENTORY.md',f'RELEASE_{version}_BUILD{code}.ru.md']
    selected_scripts=['build-signed-release.ps1','publish-github-draft.ps1','prepare-distribution.py']
    paths=[ROOT/name for name in root_files]+[ROOT/'docs'/name for name in selected_docs]+[ROOT/'scripts'/name for name in selected_scripts]
    for folder in ['app/src','app/libs','gradle','vpnprobe/src','docs/user-guide','docs/legal']:
        paths.extend(p for p in (ROOT/folder).rglob('*') if p.is_file())
    entries={}
    for path in paths:
        if not path.is_file(): raise RuntimeError('Required source file missing: '+str(path.relative_to(ROOT)))
        rel=path.relative_to(ROOT).as_posix()
        if any(part in {'build','.git','.gradle','x86_64','__pycache__'} for part in path.relative_to(ROOT).parts): continue
        data=path.read_bytes();scan(rel,data,secrets);entries[rel]=data
    entries['SOURCES.sha256']=''.join(sha(data)+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
    archive(out/source,entries)
    write(out/(source+'.sha256'),sha((out/source).read_bytes())+'  '+source+'\n')
    for lang,name in [('ru','index.html'),('en','index-en.html')]:
        write(out/name,page(lang,version,code,apk,source,checked['apk'],(out/apk).stat().st_size/1_000_000))
    public=[apk,apk+'.sha256',source,source+'.sha256','LICENSE','android-notices.txt','native-notices.txt',
            'signing-certificate-sha256.txt','privacy.html','privacy-en.html','RELEASE_NOTES.md']
    manifest=dict(package='vikify.vpn',publisher='VictoriaV',version=version,versionCode=code,minSdk=26,targetSdk=36,
        abi=['arm64-v8a'],repository=REPO,tag=f'vikify-v{version}-build{code}',signingCertificateSha256=CERT,
        assets=[dict(name=name,sha256=sha((out/name).read_bytes()),bytes=(out/name).stat().st_size,
                     kind='source' if name==source else 'apk' if name==apk else 'support') for name in public])
    write(out/'release-manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    instructions=f'''# Комплект Vikify {version} (build {code})

Статус: подготовлено локально. APK, страницы загрузки и GitHub Release не опубликованы этим комплектом.

## Сайт

Архив `Vikify-{version}-build{code}-website.zip` содержит готовые файлы для `https://94043.net/vikify/`.
Перед размещением сохраните текущую папку сайта. Скопируйте содержимое архива в выделенную папку `/vikify/` без удаления других разделов.
При хостинге Next.js проверьте новые статические пути после штатного обновления приложения сервера.
Проверьте анонимные HTTP 200 для обеих HTML-страниц, APK, контрольной суммы и обеих политик.
Сравните SHA-256 скачанного APK с локальным манифестом. Сервер должен отдавать APK как файл, а не HTML-страницу ошибки.
Политики уже опубликованы; комплект содержит их совпадающие копии. AAB и служебные символы в сайт не включены.

## GitHub Releases

Репозиторий: https://github.com/{REPO}. Основная ветка содержит старый Xray-проект.
Скрипт создаёт отдельную ветку `codex/vikify-{version}-build{code}` из проверенного снимка исходников и **черновик** релиза; master/default branch не изменяется.
Исходная ветка имеет самостоятельную историю. ZIP содержит `SOURCES.sha256`, ARM64-библиотеки и документы лицензий.

Распакуйте `Vikify-{version}-build{code}-github.zip`. В PowerShell, из папки комплекта:

```powershell
.\\publish-github-draft.ps1 -KitDirectory .
# После установки GitHub CLI и входа в аккаунт с правом записи в GradeApp/Vikify:
gh auth login --hostname github.com
.\\publish-github-draft.ps1 -KitDirectory . -CreateDraft
```

Нужны Git, GitHub CLI, настроенные git user.name/user.email и авторизация Git для HTTPS push (при необходимости `gh auth setup-git`).
Скрипт не перезаписывает существующие ветки/теги, не публикует черновик и сохраняет рабочую папку при сбое. После частичного сетевого сбоя осмотрите созданную ветку/черновик перед повтором.
Перед публикацией проверьте страницу черновика, APK/исходники/лицензии и контрольные суммы. Не добавляйте ключи подписи, реальные подписки, QA-QR, AAB или R8 mapping в публичный релиз.

## Издателю

AAB: `{aab}` — хранится отдельно в локальном каталоге релиза. Магазинная публикация отложена.
APK обновляет только приложение с тем же package ID и сертификатом. Не удаляйте существующий релиз для обновления.
Проверьте статус Android developer verification для выбранных стран и канала распространения перед массовой раздачей.
'''
    write(out/'PUBLISHING.ru.md',instructions)
    shutil.copyfile(ROOT/'scripts/publish-github-draft.ps1',out/'publish-github-draft.ps1')
    website=public+['index.html','index-en.html','release-manifest.json']
    github=public+['release-manifest.json','PUBLISHING.ru.md','publish-github-draft.ps1']
    for suffix,names in [('website',website),('github',github)]:
        contents={name:(out/name).read_bytes() for name in names}
        for name,data in contents.items(): scan(name,data,secrets)
        target=out.parent/f'Vikify-{version}-build{code}-{suffix}.zip'
        archive(target,contents);write(target.with_suffix('.zip.sha256'),sha(target.read_bytes())+'  '+target.name+'\n')
    print(json.dumps(dict(version=version,build=code,sourceFiles=len(entries),privateDataScan=bool(secrets),
                         websiteFiles=len(website),githubFiles=len(github),published=False)))

if __name__=='__main__': main()
