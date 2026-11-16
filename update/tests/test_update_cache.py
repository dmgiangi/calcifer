import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Lock
from update.cache import SingleFlightCache, cached


class CacheTest(unittest.TestCase):
    def test_concurrent_checks_share_one_lookup(self):
        calls = []
        guard = Lock()
        def fetch():
            with guard:
                calls.append(1)
            time.sleep(.03)
            return 'response'
        cache = SingleFlightCache()
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: cached(cache, 'same', fetch), range(8)))
        self.assertEqual(results, ['response'] * 8)
        self.assertEqual(len(calls), 1)
