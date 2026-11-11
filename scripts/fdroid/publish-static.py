"""Publish an already verified public tar, replacing signed entry points last.

Run on the web server. Takes public tar and its SHA-256 manifest as arguments.
Never accepts the private fdroid working directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import tarfile
import os

p=argparse.ArgumentParser()
p.add_argument('archive',type=Path)
p.add_argument('manifest',type=Path)
args=p.parse_args()
dest=Path('/var/www/vikify-fdroid').resolve()
assert dest.is_dir()
expected=json.loads(args.manifest.read_text())
entry_points={'repo/index.jar','repo/index-v1.jar','repo/entry.jar'}
with tempfile.TemporaryDirectory(prefix='vikify-fdroid-public-') as tmp:
    stage=Path(tmp)
    with tarfile.open(args.archive) as tar:
        for item in tar.getmembers():
            assert item.isfile() and item.name in expected
            assert not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
        tar.extractall(stage,filter='data')
    for name,digest in expected.items():
        assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==digest,name
    order=sorted(expected,key=lambda name:(name in entry_points,name=='repo/entry.jar',name))
    for name in order:
        target=dest/name
        assert target.resolve().is_relative_to(dest)
        target.parent.mkdir(parents=True,exist_ok=True)
        temp=target.with_name(target.name+'.uploading')
        temp.write_bytes((stage/name).read_bytes())
        temp.chmod(0o644)
        os.replace(temp,target)
print(f'Published {len(expected)} verified public files; signed entry points replaced last.')
