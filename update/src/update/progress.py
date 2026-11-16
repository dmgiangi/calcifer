"""Progress output with a three-line rolling view on interactive terminals."""
from collections import deque
import os
import shutil
import threading


class ProgressDisplay:
    def __init__(self, stream):
        self.stream = stream
        self.interactive = bool(stream.isatty()) and os.environ.get('TERM', '') != 'dumb'
        self.lines = deque(maxlen=3)
        self.started = False
        self.lock = threading.Lock()

    def __call__(self, message):
        # Do not interpret control sequences embedded in external data.
        text = ''.join(char for char in str(message) if char.isprintable())
        with self.lock:
            if not self.interactive:
                print(f'[check] {text}', file=self.stream, flush=True)
                return
            self.lines.append(f'[check] {text}')
            if not self.started:
                self.stream.write('\n' * 3)
                self.started = True
            width = max(1, shutil.get_terminal_size((80, 24)).columns - 1)
            self.stream.write('\x1b[3A')
            for line in list(self.lines) + [''] * (3 - len(self.lines)):
                self.stream.write('\r\x1b[2K' + line[:width] + '\n')
            self.stream.flush()
