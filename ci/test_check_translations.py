import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from check_translations import check_format, read_catalog


class AndroidFormattingContractTest(unittest.TestCase):
    def catalog(self, xml):
        with TemporaryDirectory() as directory:
            Path(directory, 'strings.xml').write_text(f'<resources>{xml}</resources>', encoding='utf-8')
            return read_catalog(Path(directory))[0]

    def test_plural_forms_are_kept_separately(self):
        catalog = self.catalog('<plurals name="days"><item quantity="one">%1$d day</item>'
                               '<item quantity="other">%1$d days</item></plurals>')
        self.assertEqual({'one': '%1$d day', 'other': '%1$d days'}, catalog['days'])

    def test_plural_forms_require_other_and_unique_known_quantities(self):
        for items in ('<item quantity="one">%1$d day</item>',
                      '<item quantity="other">%1$d days</item><item quantity="other">%1$d days</item>',
                      '<item quantity="invalid">%1$d days</item><item quantity="other">%1$d days</item>'):
            with self.subTest(items=items), self.assertRaises(ValueError):
                self.catalog(f'<plurals name="days">{items}</plurals>')

    def test_plural_forms_cannot_drop_or_change_arguments(self):
        for value in ('days', '%1$s days', ''):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.catalog(f'<plurals name="days"><item quantity="one">{value}</item>'
                             '<item quantity="other">%1$d days</item></plurals>')

    def test_resource_cannot_be_both_string_and_plurals(self):
        with self.assertRaises(ValueError):
            self.catalog('<string name="days">days</string>'
                         '<plurals name="days"><item quantity="other">days</item></plurals>')

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
