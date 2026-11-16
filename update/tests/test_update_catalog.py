import unittest
from update.catalog import annotate
from update.model import Component


class CatalogTest(unittest.TestCase):
    def test_known_strategy_and_unrecognized_declaration(self):
        known = Component('example', 'image', 'clusters/calcifer-home/app.yaml', declared='example:1.0.0', clusters=['home'])
        unknown = Component('x', 'unresolved-image', 'clusters/calcifer-home/app.yaml', clusters=['home'])
        annotate([known, unknown])
        self.assertEqual(known.metadata['strategy'], 'oci')
        self.assertIn('validators', known.metadata)
        self.assertEqual(known.status, 'unknown')
        self.assertEqual(unknown.status, 'unsupported')
        self.assertTrue(unknown.metadata['owner'])

    def test_unreachable_scope_fails_closed(self):
        item = Component('example', 'image', 'clusters/apps/unknown/base.yaml', declared='example:1.0.0')
        annotate([item])
        self.assertEqual(item.status, 'unsupported')
