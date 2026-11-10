#!/usr/bin/env python3
"""Bundle the public quick guide and actual demo screenshots for GitHub Releases."""
from pathlib import Path
import argparse
import zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'dist')
args = parser.parse_args()
version = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if '=' in line)['versionName']
args.output.mkdir(parents=True, exist_ok=True)
guide = (ROOT / 'docs/QUICKSTART.md').read_text()
(args.output / f'XShifter-Open-{version}-quick-guide.md').write_text(guide)
files = [ROOT / 'README.md', ROOT / 'docs/QUICKSTART.md', ROOT / 'docs/SCREENSHOTS.md']
files += sorted((ROOT / 'docs/screenshots').glob('*.png'))
if len(files) < 7:
    raise SystemExit('Expected Android Ride/Tune/battery and Mac screenshots')
archive = args.output / f'XShifter-Open-{version}-guide-and-screenshots.zip'
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
    for path in files:
        z.write(path, path.relative_to(ROOT))
print(archive)
