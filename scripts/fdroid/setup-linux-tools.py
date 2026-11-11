"""Install pinned build tools in a private WSL/Linux workspace (no signing keys)."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tarfile
import urllib.request

base=Path.home()/'vikify-fdroid-main'
tools=base/'tools'; tools.mkdir(parents=True,exist_ok=True)
sdk=base/'sdk'; sdk.mkdir(exist_ok=True)
shutil.copytree('/usr/lib/android-sdk/licenses',sdk/'licenses',dirs_exist_ok=True)
accepted=Path('/mnt/c/Users/adesp/AppData/Local/Android/Sdk/licenses')
if accepted.is_dir():
    shutil.copytree(accepted,sdk/'licenses',dirs_exist_ok=True)
version='go1.26.1'
versions=json.load(urllib.request.urlopen('https://go.dev/dl/?mode=json&include=all',timeout=60))
item=next(f for v in versions if v['version']==version for f in v['files'] if f['os']=='linux' and f['arch']=='amd64' and f['kind']=='archive')
archive=tools/item['filename']
if not archive.exists():
    print('Downloading pinned Go toolchain',flush=True)
    with urllib.request.urlopen('https://go.dev/dl/'+item['filename'],timeout=90) as response, archive.open('wb') as target:
        shutil.copyfileobj(response,target)
assert hashlib.sha256(archive.read_bytes()).hexdigest()==item['sha256']
if not (tools/version/'bin/go').is_file():
    with tarfile.open(archive) as tar:
        tar.extractall(tools/'go-extract',filter='data')
    shutil.move(str(tools/'go-extract/go'),str(tools/version))
(base/'go-toolchain.json').write_text(json.dumps(item,indent=2)+'\n')
print('Verified Go SHA256: '+item['sha256'],flush=True)
log=base/'sdk-install.log'
print('Installing SDK 36, build-tools 36.0.0 and NDK 28.2.13676358',flush=True)
with log.open('w') as output:
    subprocess.run(['/usr/lib/android-sdk/cmdline-tools/11.0/bin/sdkmanager','--sdk_root='+str(sdk),
        'platforms;android-36','build-tools;36.0.0','ndk;28.2.13676358'],stdout=output,stderr=subprocess.STDOUT,check=True)
assert (sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang').exists()
assert (sdk/'platforms/android-36/android.jar').exists()
print('Pinned Linux toolchain ready',flush=True)
