import unittest
from check_translations import tokens


class FormatSafetyTest(unittest.TestCase):
    def test_positional_reordering_is_allowed(self):
        self.assertEqual(tokens('%1$d devices at %2$s'), tokens('%2$s: %1$d devices'))

    def test_stray_percent_is_rejected(self):
        for malformed in ('%1$d%%%', '%', '%1$', 'value %q?'):
            with self.assertRaises(ValueError):
                tokens(malformed)

    def test_argument_type_and_literal_percent_are_preserved(self):
        self.assertNotEqual(tokens('%1$d%%'), tokens('%1$s%%'))
        self.assertNotEqual(tokens('%1$d%%'), tokens('%1$d'))
        with self.assertRaises(ValueError):
            tokens('%1$q')


if __name__ == '__main__':
    unittest.main()
