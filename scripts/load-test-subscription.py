"""Stream one enabled Proxy account into a disposable emulator, without printing secrets."""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--db", required=True, type=Path)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--user-id", required=True, type=int)
parser.add_argument("--import-audit", action="store_true",
                    help="Prepare the private fixture for LiveImportAuditTest on the isolated phone QA installation.")
args = parser.parse_args()
with sqlite3.connect(args.db.resolve().as_uri() + "?mode=ro", uri=True) as connection:
    row = connection.execute("SELECT token, uuid FROM users WHERE id=? AND enabled=1", (args.user_id,)).fetchone()
if row is None:
    raise SystemExit("Requested account is missing or disabled")
payload = json.dumps({"url": "https://fall.94043.net/sub/" + row[0], "uuid": row[1]}).encode()
package, filename = ("vikify.vpn.uiqa", "qa-import.json") if args.import_audit else ("vikify.vpn", "qa-subscription.json")
command = f"run-as {package} sh -c 'umask 077; mkdir -p files; cat > files/{filename}'"
result = subprocess.run([args.adb, "-s", args.serial, "shell", command], input=payload, capture_output=True)
if result.returncode:
    raise SystemExit("Could not stream credentials to the test app")
print(f"Loaded enabled account {args.user_id} into app-private storage on {args.serial}")
