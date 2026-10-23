"""Verify synthetic emulator evidence; never reads a live device or account."""
import argparse
import json
import sqlite3
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('evidence_directory')
args = parser.parse_args()
root = Path(args.evidence_directory).resolve()
connections = []
report = {}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


try:
    for label in ('before', 'after'):
        path = root / (label + '-upgrade-ayu-data')
        db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)
        connections.append(db)
        integrity = db.execute('PRAGMA integrity_check').fetchone()[0]
        schema = db.execute('PRAGMA user_version').fetchone()[0]
        identity = db.execute('SELECT identity_hash FROM room_master_table WHERE id=42').fetchone()[0]
        require(integrity == 'ok' and schema == 35, label + ': invalid database')
        require(identity == '388c57706282c0f638478a2af2440240', label + ': wrong Room identity')
        report[label] = dict(integrity=integrity, schema=schema, room_identity=identity)
    before, after = connections
    for table in ('DeletedMessage', 'EditedMessage', 'RegexFilter'):
        old = before.execute('SELECT * FROM ' + table).fetchall()
        new = after.execute('SELECT * FROM ' + table).fetchall()
        require(old == new and len(new) == 1, table + ': fixture changed during upgrade')
        report[table] = dict(rows=len(new), identical=True)
    for label in ('before', 'after'):
        prefs = {e.get('name'): e.get('value', e.text) for e in
                 ET.parse(root / (label + '-upgrade-nkmrcfg.xml')).getroot()}
        require(prefs.get('DisableAds') == 'true', label + ': DisableAds not preserved')
        require(prefs.get('NooagramUpgradeFixture') == 'synthetic-upgrade-2026-10-23',
                label + ': synthetic marker not preserved')
    report['preferences'] = dict(DisableAds_preserved=True, synthetic_marker_preserved=True)
    report['scope'] = ('Synthetic schema-35 data in logged-out isolated emulator only. '
                       'Does not validate real accounts, actual TL deserialization, '
                       'or Room opening at runtime.')
    result = json.dumps(report, ensure_ascii=False, indent=2)
    (root / 'upgrade-data-verification.json').write_text(result + chr(10), encoding='utf-8')
    print(result)
finally:
    for db in connections:
        db.close()
