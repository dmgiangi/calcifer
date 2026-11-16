import io
import unittest
from unittest.mock import patch

from update.progress import ProgressDisplay


class FakeTerminal(io.StringIO):
    def isatty(self):
        return True


class ProgressDisplayTest(unittest.TestCase):
    def test_interactive_view_reuses_three_lines(self):
        stream = FakeTerminal()
        with patch.dict('os.environ', {'TERM': 'xterm'}):
            display = ProgressDisplay(stream)
            for number in range(4):
                display(f'passo {number}')
        output = stream.getvalue()
        self.assertEqual(output.count('\x1b[3A'), 4)
        last_screen = output.rsplit('\x1b[3A', 1)[1]
        self.assertNotIn('passo 0', last_screen)
        for number in (1, 2, 3):
            self.assertIn(f'passo {number}', last_screen)
        self.assertEqual(last_screen.count('\x1b[2K'), 3)

    def test_noninteractive_remains_plain_log(self):
        stream = io.StringIO()
        display = ProgressDisplay(stream)
        display('uno')
        display('due')
        self.assertEqual(stream.getvalue(), '[check] uno\n[check] due\n')
