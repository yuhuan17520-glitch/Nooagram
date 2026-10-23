"""Synthetic data only. Never reads an account or touches a connected phone."""
import argparse
import json
import sqlite3
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('destination', type=Path)
args = parser.parse_args()
args.destination.mkdir(parents=True, exist_ok=True)
schema = json.loads((Path(__file__).resolve().parent.parent /
    'TMessagesProj/schemas/com.radolyn.ayugram.database.AyuDatabase/35.json').read_text())['database']
path = args.destination / 'ayu-data'
if path.exists():
    raise RuntimeError('Refusing to overwrite an upgrade fixture')
db = sqlite3.connect(path)
for entity in schema['entities']:
    table = entity['tableName']
    db.execute(entity['createSql'].replace(chr(36) + '{TABLE_NAME}', table))
    for index in entity.get('indices', []):
        db.execute(index['createSql'].replace('$' + '{TABLE_NAME}', table))
for sql in schema['setupQueries']:
    db.execute(sql)
for table in ('DeletedMessage', 'EditedMessage'):
    entity = next(e for e in schema['entities'] if e['tableName'] == table)
    values = {}
    for field in entity['fields']:
        if field.get('notNull'):
            values[field['columnName']] = '' if field['affinity'] == 'TEXT' else 0
    values.update(fakeId=1, userId=1000, dialogId=-2000, messageId=300,
        text='upgrade-fixture 中文 ' + table, documentSerialized=b'fixture-not-live-TL')
    db.execute(f'INSERT INTO {table} ({",".join(values)}) VALUES ({",".join("?" for _ in values)})',
        list(values.values()))
db.execute("INSERT INTO RegexFilter(id,text,dialogId,enabled,caseInsensitive,reversed) VALUES('upgrade-qa','upgrade_fixture',NULL,1,1,0)")
db.execute('PRAGMA user_version=35')
db.commit()
assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
db.close()
print('Created synthetic schema-35 archive, edit history and regex fixture:', path)
