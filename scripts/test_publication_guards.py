"""Read-only checks for LivePhoto's publication policy.

Requires PyYAML. Run from the repository with:
    python3 -m unittest discover -s scripts -p test_publication_guards.py
"""

import os
import re
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def load_workflow(name, override):
    # Overrides let a reviewer verify a proposed workflow from another checkout.
    path = Path(os.environ.get(override) or ROOT / ".github" / "workflows" / name)
    raw = path.read_text(encoding="utf-8")
    # BaseLoader preserves GitHub's "on" key rather than treating it as a boolean.
    return yaml.load(raw, Loader=yaml.BaseLoader), raw


def condition_clauses(condition):
    """Compare each conjunction rather than emulate Actions expression semantics."""
    condition = condition.strip()
    if condition.startswith("${{") and condition.endswith("}}"):
        condition = condition[3:-2].strip()
    return {" ".join(clause.split()) for clause in condition.split("&&")}


class PublicationGuards(unittest.TestCase):
    def test_android_release_requires_verified_main_and_an_explicit_dispatch_flag(self):
        workflow, raw = load_workflow("android.yml", "LIVEPHOTO_ANDROID_WORKFLOW")
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(set(workflow["on"]), {"push", "pull_request", "workflow_dispatch"})
        flag = workflow["on"]["workflow_dispatch"]["inputs"]["publish_apks"]
        self.assertEqual((flag["type"], flag["default"]), ("boolean", "false"))

        publish = workflow["jobs"]["publish"]
        self.assertEqual(publish["needs"], "android")
        self.assertEqual(condition_clauses(publish["if"]), {
            "github.ref == 'refs/heads/main'",
            "github.event_name == 'workflow_dispatch'",
            "inputs.publish_apks == true",
        })
        self.assertEqual(publish["permissions"], {"actions": "read", "contents": "write"})
        release = re.compile(r"\bgh\s+release\s+create\b")
        self.assertEqual(len(release.findall(raw)), 1)
        self.assertEqual(sum(bool(release.search(step.get("run", ""))) for step in publish["steps"]), 1)
        self.assertTrue(any(step.get("name") == "Verify fresh tests and installable APK evidence"
                            for step in workflow["jobs"]["android"]["steps"]))

    def test_pages_publish_is_manual_on_main_and_has_no_default_deployment_credential(self):
        workflow, raw = load_workflow("pages-sync.yml", "LIVEPHOTO_PAGES_WORKFLOW")
        self.assertEqual(set(workflow["on"]), {"push", "pull_request", "workflow_dispatch"})
        source = workflow["on"]["workflow_dispatch"]["inputs"]["source_run"]
        self.assertEqual((source["type"], source["required"]), ("string", "true"))
        self.assertEqual(workflow["permissions"], {"contents": "read", "actions": "read"})
        self.assertEqual(set(workflow["jobs"]), {"sync"})
        sync = workflow["jobs"]["sync"]
        self.assertNotIn("permissions", sync)
        steps = sync["steps"]
        publish = [step for step in steps if "scripts/sync-pages.py" in step.get("run", "")]
        self.assertEqual(len(publish), 1)
        self.assertEqual(condition_clauses(publish[0]["if"]), {
            "github.ref == 'refs/heads/main'",
            "github.event_name == 'workflow_dispatch'",
        })
        self.assertEqual(publish[0]["env"]["SOURCE_RUN"], "${{ inputs.source_run }}")
        self.assertEqual(publish[0]["env"]["PAGES_HTTPS_TOKEN"], "${{ secrets.PAGES_HTTPS_TOKEN }}")
        self.assertEqual(re.findall(r"secrets\.[A-Za-z0-9_]+", raw), ["secrets.PAGES_HTTPS_TOKEN"])
        checkouts = [step for step in steps if step.get("uses", "").startswith("actions/checkout@")]
        self.assertEqual(len(checkouts), 1)
        self.assertEqual(checkouts[0]["with"]["persist-credentials"], "false")


if __name__ == "__main__":
    unittest.main()
