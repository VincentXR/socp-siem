import sys
import unittest
from pathlib import Path


BUILD = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BUILD))

from middleware_images import (  # noqa: E402
    CATALOG,
    FIXTURE_IMAGE_KEYS,
    SERVICE_IMAGE_KEYS,
    load_catalog,
)


class MiddlewareImagesTest(unittest.TestCase):

    def test_catalog_contains_every_runtime_and_fixture_image(self):
        catalog = load_catalog()
        self.assertTrue(CATALOG.is_file())
        self.assertTrue(set(SERVICE_IMAGE_KEYS.values()).issubset(catalog))
        self.assertTrue(set(FIXTURE_IMAGE_KEYS.values()).issubset(catalog))

    def test_catalog_images_are_pinned(self):
        catalog = load_catalog()
        for key, image in catalog.items():
            with self.subTest(key=key):
                self.assertNotRegex(image, r":latest$")
                self.assertTrue("@sha256:" in image or ":" in image.rsplit("/", 1)[-1])


if __name__ == "__main__":
    unittest.main()
