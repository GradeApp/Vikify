"""Build ARM64 Xray and the tun2socks Go/JNI AAR entirely from vendored sources.

--prepare downloads pinned Go modules; the normal build runs with GOPROXY=off.
GO, ANDROID_HOME and ANDROID_NDK_HOME select the pinned Linux toolchains.
"""
import argparse
import hashlib
import io
import json
import os
import shutil
from pathlib import Path
import subprocess
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--prepare',action='store_true')
parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='arm64-v8a')
args=parser.parse_args()
go_arch='arm64' if args.abi=='arm64-v8a' else 'amd64'
clang_target='aarch64' if args.abi=='arm64-v8a' else 'x86_64'
root=Path(__file__).resolve().parents[1]
versions=json.loads((root/'native/versions.json').read_text())
go=shutil.which(os.environ.get('GO','go'))
assert go, 'Pinned Go compiler not found; set GO to its absolute path'
version=subprocess.check_output([go,'version'],text=True).split()[2]
assert version==versions['go'],f'Required {versions["go"]}, found {version}'
env=dict(os.environ,GOTOOLCHAIN='local',GOFLAGS='-buildvcs=false',CGO_ENABLED='1')
env['JAVA_HOME']=env.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64')
env['PATH']=str(Path(go).resolve().parent)+':'+env['JAVA_HOME']+'/bin:'+env['PATH']
sources=[root/'native/xray-core',root/'native/tun2socks']
if args.prepare:
    for source in sources:
        # gomobile constructs a temporary module from the complete module graph.
        # Lazy downloads leave indirect go.mod files missing in offline builds.
        subprocess.run([go,'mod','download','all'],cwd=source,env=env,check=True)
        subprocess.run([go,'mod','graph'],cwd=source,env=env,stdout=subprocess.DEVNULL,check=True)
        subprocess.run([go,'mod','verify'],cwd=source,env=env,check=True)
    # gomobile expands every selected module into a direct requirement in its
    # temporary gobind module. Prepare the same graph (including old .mod files
    # hidden by lazy pruning in the original module) before the offline build.
    graph=subprocess.check_output([go,'list','-m','-json','-tags=android','all'],
        cwd=sources[1],env=dict(env,GOOS='android',GOARCH=go_arch),text=True)
    decoder=json.JSONDecoder(); modules=[]
    while graph.strip():
        item,offset=decoder.raw_decode(graph.lstrip()); modules.append(item)
        graph=graph.lstrip()[offset:]
    cache_module=root/'build/native-tools/module-cache'
    cache_module.mkdir(parents=True,exist_ok=True)
    lines=['module gobind','go '+versions['go'].removeprefix('go')]
    for module in modules:
        if module.get('Main'):
            lines.append('replace '+module['Path']+' => '+module['Dir'])
        else:
            lines.append('require '+module['Path']+' '+module['Version'])
    (cache_module/'go.mod').write_text('\n'.join(lines)+'\n')
    subprocess.run([go,'mod','download','all'],cwd=cache_module,env=env,check=True)
    subprocess.run([go,'mod','verify'],cwd=cache_module,env=env,check=True)
    print('Pinned native modules downloaded and verified')
    raise SystemExit(0)
env.update(GOPROXY='off',GOSUMDB='off')
subprocess.run([go,'list','-m','-tags=android','all'],cwd=sources[1],
    env=dict(env,GOOS='android',GOARCH=go_arch),stdout=subprocess.DEVNULL,check=True)
ndk=Path(env['ANDROID_NDK_HOME'])
assert ('Pkg.Revision = '+versions['ndk']) in (ndk/'source.properties').read_text()
out=root/'app/build/native'
(out/'jniLibs'/args.abi).mkdir(parents=True,exist_ok=True)
bin_dir=root/'build/native-tools/bin'; bin_dir.mkdir(parents=True,exist_ok=True)
env['GOBIN']=str(bin_dir)
env['PATH']=str(bin_dir)+':'+env['PATH']
mobile_module=json.loads(subprocess.check_output([go,'list','-m','-json','golang.org/x/mobile'],
    cwd=sources[1],env=env,text=True))
mobile_copy=root/'build/native-tools/mobile'
if mobile_copy.exists():
    mobile_copy.chmod(0o755)
    for path in mobile_copy.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)
shutil.copytree(mobile_module['Dir'],mobile_copy,dirs_exist_ok=True)
mobile_copy.chmod(0o755)
for path in mobile_copy.rglob('*'):
    path.chmod(0o755 if path.is_dir() else 0o644)
subprocess.run(['patch','--batch','-p1','-i',str(root/'native/patches/gomobile-relative-modules.patch')],
    cwd=mobile_copy,check=True)
# Compile the patched generator with the original pinned dependency graph.
# The application itself still uses the unchanged mobile runtime sources.
tool_mod=root/'build/native-tools/tools.mod'
tool_mod.write_text((sources[1]/'go.mod').read_text()+'\nreplace golang.org/x/mobile => '+str(mobile_copy)+'\n')
shutil.copyfile(sources[1]/'go.sum',tool_mod.with_suffix('.sum'))
subprocess.run([go,'install','-modfile='+str(tool_mod),'golang.org/x/mobile/cmd/gomobile','golang.org/x/mobile/cmd/gobind'],cwd=sources[1],env=env,check=True)
aar=out/'tun2socks.aar'
subprocess.run([str(bin_dir/'gomobile'),'bind','-target=android/'+go_arch,'-androidapi=26','-trimpath',
    '-ldflags=-s -w -buildid=','-o',str(aar),'./engine'],cwd=sources[1],env=env,check=True)

def normalize_zip(data):
    result=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as src, zipfile.ZipFile(result,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as dst:
        for name in sorted(src.namelist()):
            body=src.read(name)
            if name.endswith('.jar'): body=normalize_zip(body)
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o100644<<16
            dst.writestr(info,body)
    return result.getvalue()
aar.write_bytes(normalize_zip(aar.read_bytes()))
cc=ndk/('toolchains/llvm/prebuilt/linux-x86_64/bin/'+clang_target+'-linux-android26-clang')
xray_env=dict(env,CC=str(cc),GOOS='android',GOARCH=go_arch)
xray=out/'jniLibs'/args.abi/'libxray.so'
subprocess.run([go,'build','-trimpath','-buildvcs=false','-buildmode=pie','-gcflags=all=-l=4',
    '-ldflags=-s -w -buildid= -X github.com/xtls/xray-core/core.build=12ee51e -extldflags=-Wl,-z,max-page-size=16384',
    '-o',str(xray),'./main'],cwd=sources[0],env=xray_env,check=True)
manifest={'toolchains':versions,'abi':args.abi,'files':{p.relative_to(out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in [aar,xray]}}
(out/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest['files']))
