#!/usr/bin/env python3
import copy
import hashlib
import unittest

from generate_test_trust_fixture import canonical, fixture_source_descriptors
from generate_trusted_metadata import catalog_network_policies
from release_catalog import load_catalog, metadata_for_release


class TestTrustFixtureTest(unittest.TestCase):
    def test_all_sources_include_complete_canonical_v2_network_policy(self):
        catalog = load_catalog()
        policies = catalog_network_policies(catalog)
        descriptors = [
            descriptor
            for release in catalog["releases"]
            for descriptor in fixture_source_descriptors(
                release,
                metadata_for_release(catalog, release),
                policies,
            )
        ]

        self.assertEqual(13, len(descriptors))
        self.assertEqual(set(policies), {source["id"] for source in descriptors})
        for source in descriptors:
            with self.subTest(source=source["id"]):
                self.assertEqual(2, source["protocol"])
                self.assertEqual(2, source["networkPolicy"]["schemaVersion"])
                self.assertEqual(
                    source["policyHash"],
                    hashlib.sha256(canonical(source["networkPolicy"])).hexdigest(),
                )

    def test_descriptor_rejects_missing_or_noncanonical_policy(self):
        catalog = load_catalog()
        release = catalog["releases"][0]
        metadata = metadata_for_release(catalog, release)
        policies = catalog_network_policies(catalog)
        source_id = release["sources"][0]["id"]

        missing = dict(policies)
        missing.pop(source_id)
        with self.assertRaisesRegex(ValueError, "missing reviewed network policy"):
            fixture_source_descriptors(release, metadata, missing)

        changed = copy.deepcopy(policies)
        changed[source_id]["namedCapabilities"] = []
        with self.assertRaisesRegex(ValueError, "network policy hash mismatch"):
            fixture_source_descriptors(release, metadata, changed)


if __name__ == "__main__":
    unittest.main()
