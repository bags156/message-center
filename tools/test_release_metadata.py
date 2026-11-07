import unittest
from release_metadata import metadata

CERT = "ab" * 32


class ReleaseMetadataTest(unittest.TestCase):
    def test_prerelease_version(self):
        self.assertEqual("0.2.0-beta.1", metadata("versionName=0.2.0-beta.1\nversionCode=3", CERT)["versionName"])
    def test_first_release(self):
        self.assertEqual(1, metadata("versionName=0.1.0\nversionCode=1", CERT)["versionCode"])

    def test_upgrade(self):
        prior = metadata("versionName=0.1.0\nversionCode=1", CERT)
        self.assertEqual(2, metadata("versionName=0.1.1\nversionCode=2", CERT, prior)["versionCode"])

    def test_reused_version_is_rejected(self):
        prior = metadata("versionName=0.1.0\nversionCode=1", CERT)
        with self.assertRaises(ValueError):
            metadata("versionName=0.1.1\nversionCode=1", CERT, prior)

    def test_changed_signer_is_rejected(self):
        prior = metadata("versionName=0.1.0\nversionCode=1", CERT)
        with self.assertRaises(ValueError):
            metadata("versionName=0.1.1\nversionCode=2", "cd" * 32, prior)

    def test_changed_package_is_rejected(self):
        prior = metadata("versionName=0.1.0\nversionCode=1", CERT)
        prior["packageId"] = "another.app"
        with self.assertRaises(ValueError):
            metadata("versionName=0.1.1\nversionCode=2", CERT, prior)


if __name__ == "__main__":
    unittest.main()
