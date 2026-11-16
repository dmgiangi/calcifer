"""Share an in-flight public-source lookup between concurrent checks."""
import threading


class SingleFlightCache(dict):
    def __init__(self):
        super().__init__()
        self._guard = threading.Lock()
        self._locks = {}

    def get_or_create(self, key, factory):
        with self._guard:
            lock = self._locks.setdefault(key, threading.Lock())
        with lock:
            if key not in self:
                self[key] = factory()
            return self[key]


def cached(cache, key, factory):
    if hasattr(cache, 'get_or_create'):
        return cache.get_or_create(key, factory)
    if key not in cache:
        cache[key] = factory()
    return cache[key]
