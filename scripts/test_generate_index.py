#!/usr/bin/env python3
import unittest

from generate_index import merge_extensions


class MergeExtensionsTest(unittest.TestCase):
    def test_upsert_replaces_matching_package_and_keeps_other_packages(self):
        existing = [
            {"pkg": "tw.kevinzhang.newshub.extension.gamer", "versionCode": 1},
            {"pkg": "tw.kevinzhang.newshub.extension.twocat", "versionCode": 1},
        ]
        generated = [
            {"pkg": "tw.kevinzhang.newshub.extension.twocat", "versionCode": 2},
        ]

        self.assertEqual(
            [
                {"pkg": "tw.kevinzhang.newshub.extension.gamer", "versionCode": 1},
                {"pkg": "tw.kevinzhang.newshub.extension.twocat", "versionCode": 2},
            ],
            merge_extensions(existing, generated),
        )


if __name__ == "__main__":
    unittest.main()
