#!/usr/bin/env python3
import unittest

from generate_index import merge_extensions


LEGACY_PACKAGE = "tw.kevinzhang.newshub.extension.site2cat"
TWOCAT_PACKAGE = "tw.kevinzhang.newshub.extension.twocat"


class MergeExtensionsTest(unittest.TestCase):
    def test_twocat_retires_site2cat_index_entry_without_touching_apk_files(self):
        existing = [
            {"pkg": LEGACY_PACKAGE, "apkName": "newshub-site2cat-v0.0.1.apk"},
            {"pkg": "tw.kevinzhang.newshub.extension.gamer", "apkName": "gamer.apk"},
        ]
        generated = [{"pkg": TWOCAT_PACKAGE, "apkName": "newshub-twocat-v0.0.2.apk"}]

        merged = merge_extensions(existing, generated)

        self.assertEqual(
            ["tw.kevinzhang.newshub.extension.gamer", TWOCAT_PACKAGE],
            [extension["pkg"] for extension in merged],
        )

    def test_site2cat_stays_listed_until_twocat_is_generated(self):
        existing = [{"pkg": LEGACY_PACKAGE}]

        self.assertEqual(existing, merge_extensions(existing, []))


if __name__ == "__main__":
    unittest.main()
