"""Keep the bundled offline policy identical to the reviewed website text."""
from html.parser import HTMLParser
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parent.parent

class PolicyText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.sections = {}
        self.active = None
        self.href = None
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'section':
            self.active = {'english': 'en', 'russian': 'ru'}.get(attrs.get('id'))
            if self.active:
                self.sections[self.active] = []
        if self.active and tag == 'a':
            self.href = attrs.get('href')
    def handle_data(self, data):
        if self.active:
            self.sections[self.active].append(data)
    def handle_endtag(self, tag):
        if not self.active:
            return
        if tag == 'a' and self.href and not self.href.startswith('mailto:'):
            self.sections[self.active].append(f' ({self.href})')
        if tag == 'a':
            self.href = None
        if tag in {'p', 'h2', 'h3', 'li'}:
            self.sections[self.active].append('\n\n')
        if tag == 'section':
            self.active = None

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    policy = PolicyText()
    policy.feed((ROOT / 'distribution/google-play/privacy.html').read_text(encoding='utf-8-sig'))
    if set(policy.sections) != {'en', 'ru'}:
        raise SystemExit('Both policy languages are required')
    for language, parts in policy.sections.items():
        paragraphs = [' '.join(part.split()) for part in ''.join(parts).split('\n\n')]
        text = '\n\n'.join(part for part in paragraphs if part).strip() + '\n'
        path = ROOT / f'app/src/play/assets/privacy.{language}.txt'
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8', newline='\n')
        elif not path.exists() or path.read_text(encoding='utf-8') != text:
            raise SystemExit(f'Bundled policy differs: {path.name}; run --write after review')
    print('Both offline policies match the website text')

if __name__ == '__main__':
    main()
