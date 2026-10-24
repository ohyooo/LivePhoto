"""Publish only a successful main-branch Web artifact; never build or run its contents."""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

SOURCE = 'ohyooo/LivePhoto'
SITE = 'ohyooo/livephoto-web'


def api(path):
    request = urllib.request.Request('https://api.github.com/repos/' + path,
        headers={'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + os.environ['GH_TOKEN']})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def git(directory, *arguments):
    return subprocess.check_output(['git', '-C', str(directory), *arguments], text=True).strip()


def download(artifact, destination):
    # Resolve the signed asset URL without forwarding the API token to storage.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, message, headers, url):
            return None
    request = urllib.request.Request(f'https://api.github.com/repos/{SOURCE}/actions/artifacts/{artifact["id"]}/zip',
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN']})
    try:
        urllib.request.build_opener(NoRedirect).open(request, timeout=30)
        raise RuntimeError('Expected a signed artifact redirect')
    except urllib.error.HTTPError as response:
        if response.code != 302:
            raise
        location = response.headers['Location']
    url = urllib.parse.urlsplit(location)
    if url.scheme != 'https' or not url.hostname or not url.hostname.endswith(('.blob.core.windows.net', '.githubusercontent.com')):
        raise RuntimeError('Unexpected artifact storage origin')
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(location, timeout=60) as response, destination.open('wb') as output:
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            count += len(chunk)
            if count > 100 * 1024 * 1024:
                raise RuntimeError('Website archive exceeds download budget')
            digest.update(chunk)
            output.write(chunk)
    if count != artifact['size_in_bytes'] or 'sha256:' + digest.hexdigest() != artifact['digest']:
        raise RuntimeError('Artifact archive digest or size mismatch')


def stage(archive, directory, source):
    seen = set()
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) > 512 or sum(entry.file_size for entry in entries) > 256 * 1024 * 1024:
            raise RuntimeError('Website archive exceeds extraction budget')
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
                raise RuntimeError('Unsafe archive entry')
            if entry.is_dir():
                continue
            if entry.filename in seen:
                raise RuntimeError('Duplicate archive entry')
            seen.add(entry.filename)
            if entry.filename == 'web-ui-package-evidence.json':
                target = directory / entry.filename
            elif path.parts[0] == 'web-ui' and len(path.parts) > 1:
                if path.suffix.lower() in ('.jpg', '.jpeg', '.mp4', '.mov', '.heic', '.heif', '.apk', '.klib'):
                    raise RuntimeError('Test media or unrelated binary in website archive')
                target = directory / 'site' / Path(*path.parts[1:])
            else:
                raise RuntimeError('Unrelated file in website archive')
            target.parent.mkdir(parents=True, exist_ok=True)
            with package.open(entry) as incoming, target.open('wb') as output:
                shutil.copyfileobj(incoming, output, 65536)
    manifest = json.loads((directory / 'web-ui-package-evidence.json').read_text(encoding='utf-8-sig'))
    if manifest['commit'] != source['head_sha'] or str(manifest['run']) != str(source['id']):
        raise RuntimeError('Website source identity mismatch')
    return manifest


def main():
    run = os.environ['SOURCE_RUN']
    if not re.fullmatch(r'[1-9][0-9]*', run):
        raise RuntimeError('Invalid source run ID')
    source = api(f'{SOURCE}/actions/runs/{run}')
    if not (source['conclusion'] == 'success' and source['head_branch'] == 'main' and
            source['event'] in ('push', 'workflow_dispatch') and source['path'] == '.github/workflows/web.yml' and
            source['head_repository']['full_name'] == SOURCE and re.fullmatch(r'[0-9a-f]{40}', source['head_sha'])):
        raise RuntimeError('Only a successful main-branch source Web run may publish')
    with tempfile.TemporaryDirectory(prefix='livephoto-pages-') as temporary:
        root = Path(temporary)
        site = root / 'repository'
        subprocess.run(['git', 'clone', '--depth=1', '--branch=main', f'git@github.com:{SITE}.git', str(site)], check=True)
        current = json.loads((site / 'web-ui-package-evidence.json').read_text(encoding='utf-8-sig'))
        if int(current['run']) >= int(run):
            if str(current['run']) == run and current['commit'] != source['head_sha']:
                raise RuntimeError('Published source identity disagrees with this run')
            print('PAGES_SYNC=UNCHANGED: same or newer source is already published')
            return
        name = f'LivePhoto-web-ui-{run}-{source["run_attempt"]}'
        matches = [entry for entry in api(f'{SOURCE}/actions/runs/{run}/artifacts?per_page=100')['artifacts'] if entry['name'] == name]
        if len(matches) != 1 or matches[0]['expired'] or matches[0]['workflow_run']['head_sha'] != source['head_sha']:
            raise RuntimeError('Missing, expired or ambiguous verified website artifact')
        archive = root / 'website.zip'
        download(matches[0], archive)
        staged = root / 'staged'
        stage(archive, staged, source)
        if git(site, 'status', '--porcelain'):
            raise RuntimeError('Refusing to replace an edited website checkout')
        # Reuse the site's validation instead of maintaining another hash/manifest validator.
        subprocess.run(['python3', 'scripts/verify-package.py'], cwd=site, check=True)
        shutil.rmtree(site / 'site')
        shutil.copytree(staged / 'site', site / 'site')
        shutil.copy2(staged / 'web-ui-package-evidence.json', site / 'web-ui-package-evidence.json')
        subprocess.run(['python3', 'scripts/verify-package.py'], cwd=site, check=True)
        git(site, 'config', 'user.name', 'github-actions[bot]')
        git(site, 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
        git(site, 'add', '--', 'site', 'web-ui-package-evidence.json')
        git(site, 'commit', '-m', f'Publish tested Web run {run} ({source["head_sha"][:7]})')
        commit = git(site, 'rev-parse', 'HEAD')
        git(site, 'push', 'origin', 'HEAD:main')
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            runs = api(f'{SITE}/actions/workflows/pages.yml/runs?head_sha={commit}&event=push&per_page=10')['workflow_runs']
            if runs and runs[0]['status'] == 'completed':
                if runs[0]['conclusion'] != 'success':
                    raise RuntimeError('Pages deployment or public browser checks failed: ' + runs[0]['html_url'])
                print('PAGES_SYNC=SUCCESS: ' + runs[0]['html_url'])
                return
            time.sleep(15)
        raise RuntimeError('Timed out awaiting Pages deployment and public browser checks')


if __name__ == '__main__':
    main()
