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
PLURAL_QUANTITIES = frozenset('zero one two few many other'.split())


def resource_directory(tag):
    if tag == 'en':
        return 'values'
    if tag == 'id':
        return 'values-in'
    return 'values-b+' + tag.replace('-', '+') if '-' in tag else 'values-' + tag


def read_catalog(directory):
    result, shared, literal = {}, set(), set()
    for path in sorted(directory.glob('*.xml')):
        for element in ET.parse(path).getroot():
            if element.tag not in ('string', 'plurals'):
                continue
            name = element.attrib['name']
            if name in result:
                raise ValueError(f'{path}: duplicate resource {name}')
            if element.tag == 'plurals':
                forms = {}
                for item in element:
                    quantity = item.attrib.get('quantity')
                    if item.tag != 'item' or quantity not in PLURAL_QUANTITIES or quantity in forms:
                        raise ValueError(f'{path}/{name}: invalid or duplicate plural quantity {quantity}')
                    forms[quantity] = ''.join(item.itertext())
                if 'other' not in forms:
                    raise ValueError(f'{path}/{name}: missing other plural form')
                for quantity, text in forms.items():
                    if not text.strip() or check_format(forms['other'], text, False, False):
                        raise ValueError(f'{path}/{name}/{quantity}: empty text or inconsistent format placeholders')
                result[name] = forms
            else:
                result[name] = ''.join(element.itertext())
            if element.attrib.get('translatable') == 'false':
                shared.add(name)
            if element.attrib.get('formatted') == 'false':
                literal.add(name)
    return result, shared, literal


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


def check_format(base, translated, base_literal, translated_literal):
    # Android explicitly disables Formatter for formatted="false" strings. A literal 90% is
    # correct there; forcing %% would change what getString(id) displays to the user.
    if base_literal != translated_literal:
        return 'formatted attribute differs'
    if not base_literal and tokens(base) != tokens(translated):
        return 'format placeholders differ'
    return None


def main():
    tags = [entry.attrib[ANDROID + 'name'] for entry in ET.parse(RES / 'xml/locales_config.xml').getroot()]
    if len(tags) != len(set(tags)) or set(tags) != EXPECTED_LOCALES:
        raise ValueError('Locale configuration differs from the declared 22-language / 23-locale contract')
    base, shared, literal = read_catalog(RES / 'values')
    expected = set(base) - shared
    errors = []
    for tag in tags:
        if tag == 'en':
            continue
        localized, localized_shared, localized_literal = read_catalog(RES / resource_directory(tag))
        required = expected
        if not required <= set(localized) or set(localized) - expected:
            errors.append(f'{tag}: missing={sorted(required - set(localized))}, extra={sorted(set(localized) - expected)}')
        if localized_shared:
            errors.append(f'{tag}: non-translatable strings must be inherited from the default')
        for key in expected & set(localized):
            if isinstance(base[key], dict) != isinstance(localized[key], dict):
                errors.append(f'{tag}/{key}: resource type differs (string/plurals)')
                continue
            source = base[key]['other'] if isinstance(base[key], dict) else base[key]
            forms = localized[key] if isinstance(localized[key], dict) else {'string': localized[key]}
            for quantity, text in forms.items():
                label = f'{tag}/{key}/{quantity}'
                if not text.strip():
                    errors.append(f'{label}: empty translation')
                try:
                    problem = check_format(source, text, key in literal, key in localized_literal)
                    if problem:
                        errors.append(f'{label}: {problem}')
                except ValueError as error:
                    errors.append(f'{label}: {error}')
                if tag != 'ru' and re.search('[А-Яа-яЁё]', text):
                    errors.append(f'{label}: Russian text leaked into another locale')
                if '\ufffd' in text:
                    errors.append(f'{label}: Unicode replacement character')
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
    plurals = sum(isinstance(base[key], dict) for key in expected)
    print(f'Translations verified: {len(tags)} locales, {len(expected) - plurals} strings and {plurals} plurals each, {len(shared)} shared strings')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, ET.ParseError) as error:
        print(error)
        sys.exit(1)
