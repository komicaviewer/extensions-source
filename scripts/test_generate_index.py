#!/usr/bin/env python3
import unittest

from generate_index import build_index, merge_extensions


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

    def test_replace_keeps_exact_generated_package_set(self):
        existing = [
            {"pkg": "tw.kevinzhang.newshub.extension.gamer", "versionCode": 1},
            {"pkg": "tw.kevinzhang.newshub.extension.legacy", "versionCode": 1},
            {"pkg": "tw.kevinzhang.newshub.extension.twocat", "versionCode": 1},
        ]
        generated = [
            {"pkg": "tw.kevinzhang.newshub.extension.sora", "versionCode": 2},
            {"pkg": "tw.kevinzhang.newshub.extension.twocat", "versionCode": 2},
        ]

        replaced = build_index(existing, generated, replace=True)

        self.assertEqual(
            {
                "tw.kevinzhang.newshub.extension.sora",
                "tw.kevinzhang.newshub.extension.twocat",
            },
            {extension["pkg"] for extension in replaced},
        )
        self.assertEqual(generated[0], replaced[0])
        self.assertEqual(generated[1], replaced[1])


if __name__ == "__main__":
    unittest.main()
