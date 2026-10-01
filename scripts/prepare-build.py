"""Download an isolated Android build toolchain into .tools. No system settings changed."""
from pathlib import Path
import urllib.request, zipfile, hashlib, xml.etree.ElementTree as ET, json, io, argparse
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / '.tools'
DEST.mkdir(exist_ok=True)
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def get(url):
    return HTTP.open(url, timeout=90)

def install(name, url, directory, digest=None):
    target = DEST / directory
    if target.exists():
        print(name, 'already downloaded', flush=True)
        return
    archive = DEST / (name + '.zip')
    print('Downloading', name, flush=True)
    with get(url) as src, archive.open('wb') as dst:
        while chunk := src.read(1024 * 1024):
            dst.write(chunk)
    if digest:
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != digest.strip():
            raise RuntimeError(name + ' checksum mismatch')
    with zipfile.ZipFile(archive) as z:
        z.extractall(target)
    archive.unlink()
    print(name, 'ready', flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-sdk-license', action='store_true', help='Accept the Android SDK license at https://developer.android.com/studio/terms')
    args = parser.parse_args()
    if not args.accept_sdk_license:
        parser.error('Read https://developer.android.com/studio/terms and pass --accept-sdk-license to install the SDK. Android Studio is an alternative.')
    checksum = get('https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256').read().decode()
    repository = ET.fromstring(get('https://dl.google.com/android/repository/repository2-1.xml').read())
    sdk_url = None
    for package in repository.findall('remotePackage'):
        if package.get('path') == 'cmdline-tools;19.0':
            for archive in package.findall('./archives/archive'):
                if archive.findtext('host-os') == 'windows':
                    sdk_url = 'https://dl.google.com/android/repository/' + archive.findtext('./complete/url')
    if not sdk_url:
        raise RuntimeError('Could not resolve Android command line tools')
    jdks = json.load(get('https://api.azul.com/metadata/v1/zulu/packages/?java_version=17&os=windows&arch=x86&archive_type=zip&java_package_type=jdk&release_status=ga&availability_types=CA&latest=true'))
    jdk_url = next(p['download_url'] for p in jdks if p['name'].endswith('win_x64.zip') and 'crac' not in p['name'] and '-fx-' not in p['name'])
    with ThreadPoolExecutor(max_workers=3) as pool:
        tasks = [
            pool.submit(install, 'gradle', 'https://services.gradle.org/distributions/gradle-8.9-bin.zip', 'gradle', checksum),
            pool.submit(install, 'jdk64', jdk_url, 'jdk64'),
            pool.submit(install, 'android-tools19', sdk_url, 'android-tools19'),
        ]
        for future in tasks:
            future.result()
    packages = {p.get('path'): p for p in repository.findall('remotePackage')}
    sdk_root = (DEST / 'sdk').resolve()
    for key, relative in [('platforms;android-35', 'platforms/android-35'),
                          ('build-tools;35.0.0', 'build-tools/35.0.0'),
                          ('build-tools;34.0.0', 'build-tools/34.0.0'),
                          ('platform-tools', 'platform-tools')]:
        target = sdk_root / relative
        if (target / 'source.properties').exists():
            continue
        archive = next(a for a in packages[key].findall('./archives/archive') if a.findtext('host-os') in ('windows', None))
        print('Downloading', key, flush=True)
        payload = get('https://dl.google.com/android/repository/' + archive.findtext('./complete/url')).read()
        checksum = archive.findtext('./complete/checksum')
        if checksum and hashlib.sha1(payload).hexdigest() != checksum:
            raise RuntimeError(key + ' checksum mismatch')
        with zipfile.ZipFile(io.BytesIO(payload)) as z:
            prefix = z.namelist()[0].split('/')[0] + '/'
            for entry in z.infolist():
                relative_file = entry.filename[len(prefix):]
                if entry.is_dir() or not relative_file:
                    continue
                output = (target / relative_file).resolve()
                if not output.is_relative_to(sdk_root):
                    raise RuntimeError('Unsafe archive path')
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(z.read(entry))
        print('Installed', key, flush=True)
    licenses = sdk_root / 'licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for license in repository.findall('license'):
        (licenses / license.get('id')).write_text(hashlib.sha1((license.text or '').encode()).hexdigest() + '\n')
    print('Toolchain ready. Run powershell -ExecutionPolicy Bypass -File scripts/build.ps1', flush=True)

if __name__ == '__main__':
    main()
