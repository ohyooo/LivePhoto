import json
import runpy
import stat
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

module = runpy.run_path(str(Path(__file__).with_name('sync-pages.py')))
stage = module['stage']


class PagesArchiveTest(unittest.TestCase):
    source = {'id': 123, 'head_sha': 'a' * 40}

    def package(self, entries):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        archive = root / 'package.zip'
        with zipfile.ZipFile(archive, 'w') as output, warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            for name, data in entries:
                output.writestr(name, data)
        return archive, root / 'staged'

    def test_valid_archive_is_bound_to_the_successful_run(self):
        manifest = {'commit': self.source['head_sha'], 'run': '123'}
        archive, target = self.package([('web-ui/index.html', 'site'), ('web-ui-package-evidence.json', json.dumps(manifest))])
        self.assertEqual(manifest, stage(archive, target, self.source))
        self.assertEqual('site', (target / 'site/index.html').read_text())
        with self.assertRaisesRegex(RuntimeError, 'source identity'):
            stage(archive, target, {**self.source, 'id': 124})

    def test_unsafe_unrelated_and_duplicate_files_are_rejected(self):
        for name in ('../escape', '/absolute', 'web-ui/../../escape', 'web-ui\\escape', 'private.txt', 'web-ui/test.mp4'):
            with self.subTest(name=name):
                archive, target = self.package([(name, 'unsafe')])
                with self.assertRaises(RuntimeError):
                    stage(archive, target, self.source)
                self.assertFalse((target.parent / 'escape').exists())
        archive, target = self.package([('web-ui/index.html', 'one'), ('web-ui/index.html', 'two')])
        with self.assertRaisesRegex(RuntimeError, 'Duplicate'):
            stage(archive, target, self.source)

    def test_symlinks_and_archive_bombs_are_rejected(self):
        link = zipfile.ZipInfo('web-ui/link')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive, target = self.package([(link, '../outside')])
        with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
            stage(archive, target, self.source)
        archive, target = self.package([(f'web-ui/{index}', '') for index in range(513)])
        with self.assertRaisesRegex(RuntimeError, 'extraction budget'):
            stage(archive, target, self.source)

    def test_a_commit_alone_does_not_prove_public_deployment(self):
        wait = module['await_pages']
        for result in ('success', 'failure', 'cancelled'):
            response = {'workflow_runs': [{'status': 'completed', 'conclusion': result, 'html_url': 'https://github.com/example/run'}]}
            with patch.dict(wait.__globals__, {'api': lambda _: response}):
                if result == 'success':
                    wait('a' * 40)
                else:
                    with self.assertRaisesRegex(RuntimeError, 'public browser checks failed'):
                        wait('a' * 40)


if __name__ == '__main__':
    unittest.main()
