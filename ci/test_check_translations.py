import unittest
from check_translations import check_format


class AndroidFormattingContractTest(unittest.TestCase):
    def test_literal_percentage_matches_android_formatted_false(self):
        self.assertIsNone(check_format('Disk at 90%', 'Disco al 90%', True, True))

    def test_translations_cannot_silently_disable_formatting(self):
        self.assertIsNotNone(check_format('%1$d files', '%1$d fichiers', False, True))

    def test_formatted_arguments_remain_strict(self):
        self.assertIsNotNone(check_format('%1$d files', '%1$s files', False, False))
        self.assertIsNotNone(check_format('%1$d files', 'files', False, False))
        with self.assertRaises(ValueError):
            check_format('90%', '90%', False, False)


if __name__ == '__main__':
    unittest.main()
