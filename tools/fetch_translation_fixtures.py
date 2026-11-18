"""Download hash-pinned test packs into the TEST APK only. Emulator tests run offline."""
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parent.parent
catalog = json.loads((root / 'app/src/main/assets/translation-models.json').read_text())
dest = root / 'app/src/androidTest/assets/translation'
dest.mkdir(parents=True, exist_ok=True)
for pair in ('es-en', 'en-es'):
    for spec in catalog[pair].values():
        path = dest / (spec['sha256'] + '-' + spec['name'])
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == spec['sha256']:
            continue
        with urllib.request.urlopen(spec['url'], timeout=60) as response:
            content = response.read(spec['size'] + 1)
        if len(content) != spec['size'] or hashlib.sha256(content).hexdigest() != spec['sha256']:
            raise RuntimeError('Translation fixture checksum mismatch')
        path.write_bytes(content)
print('Verified Spanish ↔ English test packs; no models added to the release APK.')
