/* Verify the actual R8 DEX, not only Java classes or ProGuard configuration.
 * LockedDao and LaunchActivity both have reflective contracts which may compile
 * but fail at runtime when R8 changes their structural shape. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const dexDirectory = path.resolve(process.argv[2] || path.join(__dirname,
    '../TMessagesProj/build/intermediates/dex/release/minifyReleaseWithR8'));

function dexFiles(directory) {
    return fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
        const file = path.join(directory, entry.name);
        return entry.isDirectory() ? dexFiles(file) : entry.name.endsWith('.dex') ? [file] : [];
    });
}

function readClasses(file) {
    const data = fs.readFileSync(file);
    assert.equal(data.subarray(0, 4).toString(), 'dex\n', 'Not a DEX file: ' + file);
    assert.equal(data.readUInt32LE(40), 0x12345678, 'Unsupported DEX byte order');
    const stringOffsets = data.readUInt32LE(60);
    const types = data.readUInt32LE(68);
    const protos = data.readUInt32LE(76);
    const methods = data.readUInt32LE(92);
    const definitions = data.readUInt32LE(100);
    const cache = new Map();
    function uleb(cursor) {
        let value = 0;
        for (let shift = 0; shift < 35; shift += 7) {
            const byte = data[cursor.offset++];
            assert.notEqual(byte, undefined, 'Truncated ULEB128');
            value += (byte & 127) * 2 ** shift;
            if ((byte & 128) === 0) return value;
        }
        throw new Error('Invalid ULEB128');
    }
    function string(index) {
        if (cache.has(index)) return cache.get(index);
        const cursor = {offset: data.readUInt32LE(stringOffsets + index * 4)};
        uleb(cursor); // UTF-16 length; class descriptors and method names here are ASCII.
        const end = data.indexOf(0, cursor.offset);
        assert.ok(end >= cursor.offset, 'Truncated DEX string');
        const value = data.subarray(cursor.offset, end).toString('utf8');
        cache.set(index, value);
        return value;
    }
    function classMethods(offset) {
        if (offset === 0) return [];
        const cursor = {offset};
        const staticFields = uleb(cursor), instanceFields = uleb(cursor);
        const directMethods = uleb(cursor), virtualMethods = uleb(cursor);
        for (let i = 0; i < staticFields + instanceFields; i++) { uleb(cursor); uleb(cursor); }
        const records = [];
        for (const count of [directMethods, virtualMethods]) {
            let index = 0;
            for (let i = 0; i < count; i++) {
                index += uleb(cursor);
                uleb(cursor); uleb(cursor); // access flags, code offset
                const methodOffset = methods + index * 8;
                const protoIndex = data.readUInt16LE(methodOffset + 2);
                const parametersOffset = data.readUInt32LE(protos + protoIndex * 12 + 8);
                const parameterCount = parametersOffset === 0 ? 0 : data.readUInt32LE(parametersOffset);
                records.push({
                    name: string(data.readUInt32LE(methodOffset + 4)),
                    noArgs: parameterCount === 0
                });
            }
        }
        return records;
    }
    const result = new Map();
    for (let i = 0; i < data.readUInt32LE(96); i++) {
        const offset = definitions + i * 32;
        const name = string(data.readUInt32LE(types + data.readUInt32LE(offset) * 4));
        if (!name.startsWith('Lcom/radolyn/ayugram/database/')
                && !name.startsWith('Lcom/radolyn/ayugram/settings/')
                && !name.startsWith('Ltw/nekomimi/nekogram/settings/')) continue;
        result.set(name, {flags: data.readUInt32LE(offset + 4),
            methods: classMethods(data.readUInt32LE(offset + 24))});
    }
    return result;
}

const files = dexFiles(dexDirectory);
assert.ok(files.length > 0, 'Run :TMessagesProj:minifyReleaseWithR8 first');
const classes = new Map(files.flatMap(file => [...readClasses(file)]));
for (const [name, method] of Object.entries({DeletedMessageDao: 'getMessage',
    EditedMessageDao: 'getAllRevisions', DeletedDialogDao: 'getAll', RegexFilterDao: 'getAll', SpyDao: 'getAllLastSeen'})) {
    const prefix = 'Lcom/radolyn/ayugram/database/dao/' + name;
    const dao = classes.get(prefix + ';');
    assert.ok(dao, 'DAO missing after R8: ' + name);
    assert.ok((dao.flags & 0x200) !== 0, 'R8 merged proxy DAO interface: ' + name);
    assert.ok(classes.has(prefix + '_Impl;'), 'Generated DAO missing: ' + name);
    // All members are kept; the selected methods are a reflection contract check.
    assert.ok(dao.methods.some(candidate => candidate.name === method), name + ' method missing: ' + method);
    console.log('PASS R8 preserves proxy interface and implementation: ' + name);
}
assert.ok(classes.has('Lcom/radolyn/ayugram/database/AyuDatabase_Impl;'), 'Room database implementation missing');
console.log('PASS Room database implementation');

for (const name of [
    'com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity',
    'com.radolyn.ayugram.settings.NooagramPinnedHiderActivity',
    'tw.nekomimi.nekogram.settings.NekoGeneralSettingsActivity',
    'tw.nekomimi.nekogram.settings.NekoSettingsActivity'
]) {
    const descriptor = 'L' + name.replaceAll('.', '/') + ';';
    const settingsClass = classes.get(descriptor);
    assert.ok(settingsClass, 'Custom settings class renamed or removed after R8: ' + name);
    assert.ok(settingsClass.methods.some(method => method.name === '<init>' && method.noArgs),
        'Custom settings no-argument constructor missing after R8: ' + name);
    console.log('PASS R8 preserves custom settings restore contract: ' + name);
}
console.log('PASS inspected ' + files.length + ' release DEX files (no APK generated)');
