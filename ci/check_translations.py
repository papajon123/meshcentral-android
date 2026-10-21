"""Check complete Android translation catalogs and format argument safety."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'app/src/main/res'
TOKEN = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc]|[bBhHsScCdoxXeEfgGaA%n])')
ANDROID = '{http://schemas.android.com/apk/res/android}'
EXPECTED_LOCALES = frozenset('en ru zh-Hans zh-Hant es fr pt de ar hi bn ur ja ko id tr vi th it fa ta te mr'.split())


def resource_directory(tag):
    if tag == 'en':
        return 'values'
    if tag == 'id':
        return 'values-in'
    return 'values-b+' + tag.replace('-', '+') if '-' in tag else 'values-' + tag


def read_catalog(directory):
    result, shared = {}, set()
    for path in sorted(directory.glob('*.xml')):
        for element in ET.parse(path).getroot():
            if element.tag != 'string':
                continue
            name = element.attrib['name']
            if name in result:
                raise ValueError(f'{path}: duplicate string {name}')
            result[name] = ''.join(element.itertext())
            if element.attrib.get('translatable') == 'false':
                shared.add(name)
    return result, shared


def tokens(value):
    found = []
    position = 0
    while (position := value.find('%', position)) != -1:
        match = TOKEN.match(value, position)
        if match is None:
            raise ValueError(f'Invalid or unescaped percent sign at character {position}')
        found.append(match.group(0))
        position = match.end()
    return Counter(found)


def main():
    tags = [entry.attrib[ANDROID + 'name'] for entry in ET.parse(RES / 'xml/locales_config.xml').getroot()]
    if len(tags) != len(set(tags)) or set(tags) != EXPECTED_LOCALES:
        raise ValueError('Locale configuration differs from the declared 22-language / 23-locale contract')
    base, shared = read_catalog(RES / 'values')
    expected = set(base) - shared
    errors = []
    for tag in tags:
        if tag == 'en':
            continue
        localized, localized_shared = read_catalog(RES / resource_directory(tag))
        if set(localized) != expected:
            errors.append(f'{tag}: missing={sorted(expected - set(localized))}, extra={sorted(set(localized) - expected)}')
        if localized_shared:
            errors.append(f'{tag}: non-translatable strings must be inherited from the default')
        for key in expected & set(localized):
            text = localized[key]
            if not text.strip():
                errors.append(f'{tag}/{key}: empty translation')
            try:
                if tokens(base[key]) != tokens(text):
                    errors.append(f'{tag}/{key}: format placeholders differ')
            except ValueError as error:
                errors.append(f'{tag}/{key}: {error}')
            if tag != 'ru' and re.search('[А-Яа-яЁё]', text):
                errors.append(f'{tag}/{key}: Russian text leaked into another locale')
            if '\ufffd' in text:
                errors.append(f'{tag}/{key}: Unicode replacement character')
    # Do not let newly introduced app-authored Russian messages bypass Android resources.
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if line.lstrip().startswith(('//', '*')):
                continue
            if re.search(r'"[^"\n]*[А-Яа-яЁё]', line):
                errors.append(f'{path.relative_to(ROOT)}:{number}: hardcoded Russian message')
    if errors:
        print('\n'.join(errors))
        return 1
    print(f'Translations verified: {len(tags)} locales, {len(expected)} translated strings each, {len(shared)} shared strings')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, ET.ParseError) as error:
        print(error)
        sys.exit(1)
