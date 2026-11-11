"""Run on 94043.net after uploading the reviewed public tar and website zip."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tarfile
import zipfile

stage = Path('/root/vikify-fdroid-stage-20261107')
public = Path('/var/www/vikify-fdroid')
website = Path('/var/www/website-94043/public/vikify')
config = Path('/etc/nginx/sites-available/94043.net')
backup = Path('/root/backups/fdroid-before-20261107/website-and-nginx.tar.gz')
assert backup.is_file(), 'Server backup is required'
assert not public.exists(), 'Initial deployment must not overwrite an existing repository'
with tarfile.open(stage/'fdroid-public.tar') as archive:
    archive.extractall(public, filter='data')
for path in public.rglob('*'):
    path.chmod(0o755 if path.is_dir() else 0o644)
public.chmod(0o755)
with zipfile.ZipFile(stage/'website-upload.zip') as archive:
    for name in archive.namelist():
        assert '/' not in name and '\\' not in name and name not in ('.','..')
    archive.extractall(website)
manifest = json.loads((website/'release-manifest.json').read_text())
for asset in manifest['assets']:
    assert hashlib.sha256((website/asset['name']).read_bytes()).hexdigest() == asset['sha256']
old = config.read_text()
assert 'location ^~ /fdroid/' not in old
anchor = '    location = /cdn/media/live.m3u8 {'
assert old.count(anchor) == 1
block = '''    # Vikify publisher repository: static public files only.
    location = /fdroid { return 301 /fdroid/; }
    location = /fdroid/repo { return 301 /fdroid/repo/; }
    location ^~ /fdroid/ {
        alias /var/www/vikify-fdroid/;
        index index.html;
        autoindex off;
        expires -1;
    }
    location = /vikify { return 301 /vikify/; }
    location ^~ /vikify/ {
        alias /var/www/website-94043/public/vikify/;
        index index.html;
        autoindex off;
        expires -1;
    }

'''
config.write_text(old.replace(anchor, block+anchor))
try:
    subprocess.run(['nginx','-t'], check=True)
    subprocess.run(['systemctl','reload','nginx'], check=True)
except Exception:
    config.write_text(old)
    raise
print('Published F-Droid repository and build 23. nginx validation and reload passed.')
