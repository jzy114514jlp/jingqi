"""Prefetch the pinned Robolectric runtimes into the workspace (Windows restricted environments)."""
from pathlib import Path
import hashlib
import urllib.request

root = Path(__file__).resolve().parent.parent
cache = root / '.tools/test-home/.m2/repository'
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
for version in ('9-robolectric-4913185-2-i7', '15-robolectric-12650502-i7'):
    base = f'org/robolectric/android-all-instrumented/{version}/android-all-instrumented-{version}'
    for extension in ('pom', 'jar'):
        relative = f'{base}.{extension}'
        destination = cache / relative
        if destination.exists():
            continue
        print(f'Preparing test runtime: {version}.{extension}', flush=True)
        url = 'https://repo.maven.apache.org/maven2/' + relative
        data = http.open(url, timeout=120).read()
        expected = http.open(url + '.sha512', timeout=30).read().decode().strip()
        if hashlib.sha512(data).hexdigest() != expected:
            raise RuntimeError('Test runtime checksum mismatch')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
print('Test runtime cache ready.', flush=True)
