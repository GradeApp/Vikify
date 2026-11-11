"""Package the reviewed F-Droid candidate locally; never publishes or reads keys."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sqlite3
import zipfile

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--report-directory',type=Path,required=True)
parser.add_argument('--secrets-db',type=Path,required=True)
args=parser.parse_args()
out=args.report_directory.resolve()
assert out.is_relative_to(root/'release-artifacts')
build=(root/'app/build.gradle.kts').read_text()
version=re.search(r'versionName = "([\d.]+)"',build)[1]
code=int(re.search(r'versionCode = (\d+)',build)[1])
stem=f'Vikify-{version}-build{code}'
apk=out/(stem+'-arm64-release.apk')
verification=json.loads((out/'apk-verification.json').read_text())
assert verification['fdroidSignatureCopyVerified'] and verification['binaryScannerProblems']==0
assert verification['sha256']==hashlib.sha256(apk.read_bytes()).hexdigest()
with sqlite3.connect(args.secrets_db.resolve().as_uri()+'?mode=ro',uri=True) as db:
    secrets=[str(value).encode() for row in db.execute('select token,uuid from users') for value in row if value and len(str(value))>12]
assert secrets, 'Private-data scan requires nonempty local reference values'
files=[root/name for name in [
    '.gitignore','.gitattributes','build.gradle.kts','settings.gradle.kts','gradle.properties',
    'gradlew','gradlew.bat','keystore.properties.example','README.md','LICENSE','DISCLAIMER.md',
    'BUILD_AND_VALIDATE.md','app/build.gradle.kts','app/proguard-rules.pro','app/lint.xml',
    'vpnprobe/build.gradle.kts']]
for directory in ['app/src','native','gradle','scripts','vpnprobe/src','fastlane','fdroid','artwork','docs/legal','docs/user-guide']:
    files.extend(p for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for name in ['BUNDLED_THIRD_PARTY_NOTICES.md','RELEASE_SIGNING.md','PRIVACY_POLICY.md',
    'DATA_INVENTORY.md','SUPPORTED_DEVICES_AND_ABI.md','FDROID_BUILD.md',
    'FDROID_REPOSITORY.ru.md','FDROID_MAIN_PREPARATION.ru.md','ARTWORK_PROVENANCE.md',
    'SECURITY_REVIEW.md','PROXY_SUBSCRIPTIONS_PLAN.ru.md']:
    files.append(root/'docs'/name)
entries={}
for path in files:
    name=path.relative_to(root).as_posix(); data=path.read_bytes()
    assert not any(value in data for value in secrets), 'Private subscription data found in '+name
    assert path.suffix not in ['.so','.aar','.apk','.aab','.jks','.p12','.pem','.pyc']
    entries[name]=data
entries['SOURCES.sha256']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
def archive(path,items):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(items.items()):
            info=zipfile.ZipInfo(name,(2026,11,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=(0o100755 if name=='gradlew' else 0o100644)<<16
            z.writestr(info,data)
    with zipfile.ZipFile(path) as z: assert z.testzip() is None
source=out/(stem+'-source.zip');archive(source,entries)
submission={p.relative_to(root/'fdroid').as_posix():p.read_bytes() for p in (root/'fdroid').rglob('*') if p.is_file()}
submission['PREPARATION_REPORT.ru.md']=(root/'docs/FDROID_MAIN_PREPARATION.ru.md').read_bytes()
for name in ['reproducibility.json','apk-verification.json','galaxy-report.json','native-audit.json','lint.log','scanner-source.json','apksigner-verify.txt']:
    submission['verification/'+name]=(out/name).read_bytes()
kit=out/(stem+'-fdroid-submission.zip');archive(kit,submission)
manifest={'version':version,'versionCode':code,'package':'vikify.vpn',
 'signingCertificateSha256':'570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0',
 'published':False,'submittedToFdroid':False,'artworkRightsConfirmation':'documented; unknown-origin icon replaced',
 'privateSubscriptionScan':'passed','sourceFiles':len(entries),
 'files':{p.name:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in [apk,source,kit]}}
for name,record in manifest['files'].items():
    (out/(name+'.sha256')).write_text(record['sha256']+'  '+name+'\n')
(out/'candidate-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
backup=root/'backups/fdroid-main-before-20261111'
hashes={p.relative_to(backup).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in backup.rglob('*') if p.is_file() and p.name!='SHA256.json'}
(backup/'SHA256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print(json.dumps({'version':version,'build':code,'sourceFiles':len(entries),'privateSubscriptionScan':'passed','published':False,'files':list(manifest['files'])}))
