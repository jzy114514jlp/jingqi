"""Package only project source and intentional deliverables; never include toolchains or keys."""
from pathlib import Path
import zipfile
import hashlib

root = Path(__file__).resolve().parent.parent
out = root / 'deliverables'
out.mkdir(exist_ok=True)
source = out / 'JingQi-source.zip'
excluded = {'.tools', '.gradle', '.git', '.idea', 'build', '__pycache__', 'deliverables'}
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if not path.is_file() or set(relative.parts) & excluded:
            continue
        if any(part.startswith('.backup-') for part in relative.parts):
            continue
        # Legacy server runtime data can contain report contents and credentials; it is never source.
        if relative.parts[:2] == ('server', 'data') or path.name.startswith('.env'):
            continue
        if path.suffix in ('.pyc', '.jks', '.keystore') or path.name == 'local.properties':
            continue
        archive.write(path, relative.as_posix())

files = [out / 'JingQi-debug.apk', out / 'JingQi-Demo-debug.apk', source]
checksums = []
for path in files:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    checksums.append(f'{digest}  {path.name}')
    print(path.name, path.stat().st_size, 'bytes')
(out / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
with zipfile.ZipFile(out / 'JingQi-delivery.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in files + [out / 'START-HERE.md', out / 'SHA256SUMS.txt']:
        archive.write(path, path.name)
print('JingQi-delivery.zip is ready.')
