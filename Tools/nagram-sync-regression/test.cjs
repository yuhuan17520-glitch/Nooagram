/* Run the real proposal script with isolated git/gh doubles: no network,
 * real repository configuration, commits, pushes, issues, or PRs are touched. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'nooagram-sync-'));
const bin = path.join(temp, 'bin');
fs.mkdirSync(bin);
const quote = value => "'" + value.replaceAll("'", "'\"'\"'") + "'";
const posix = value => value.replaceAll(String.fromCharCode(92), '/');
const bashPath = value => posix(value).replace(/^([a-z]):/i, (_, drive) => '/' + drive.toLowerCase());
const bash = process.env.BASH_EXECUTABLE || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const target = 'a'.repeat(40);
const pin = 'b'.repeat(40);
fs.writeFileSync(path.join(temp, 'double.cjs'), String.raw`
const fs = require('node:fs');
const [tool, ...args] = process.argv.slice(2);
const mode = process.env.SYNC_CASE;
fs.appendFileSync(process.env.SYNC_LOG, JSON.stringify([tool, ...args]) + '\n');
const is = (...prefix) => prefix.every((v, i) => args[i] === v);
const out = v => { console.log(v); process.exit(0); };
if (tool === 'git') {
  if (is('remote', 'get-url', '--push', 'origin')) out(mode === 'unsafe-origin' ? 'https://github.com/other/repo.git' : 'https://github.com/yuhuan17520-glitch/Nooagram.git');
  if (is('status', '--porcelain')) out('');
  if (is('remote', 'get-url', 'nagram-sync')) { if (mode === 'remote-exists') out('https://github.com/NextAlone/Nagram.git'); process.exit(2); }
  if (is('remote', 'add') || is('fetch') || is('config') || is('switch') || is('add') || is('commit') || is('push')) process.exit(0);
  if (is('rev-parse', 'FETCH_HEAD')) out('a'.repeat(40));
  if (is('merge-base', '--is-ancestor')) {
    if (args[2] === 'a'.repeat(40)) process.exit(mode === 'current' ? 0 : 1);
    if (mode === 'rewritten' && args[3] === 'a'.repeat(40)) process.exit(1);
    process.exit(0);
  }
  if (is('ls-remote')) { if (mode === 'remote-error') process.exit(128); out(['branch-exists', 'pr-exists', 'pr-query-error'].includes(mode) ? 'abc\trefs/heads/sync/nagram-aaaaaaaaaaaa' : ''); }
  if (is('merge', '--abort')) process.exit(0);
  if (is('merge')) process.exit(mode === 'conflict' ? 1 : 0);
  if (is('diff')) out('TMessagesProj/src/main/java/Fixture.java');
  if (is('show')) out('def verCode = 1256');
}
if (tool === 'gh') {
  if (is('pr', 'list')) { if (mode === 'pr-query-error') process.exit(1); out(mode === 'pr-exists' ? '1' : '0'); }
  if (is('pr', 'create') || is('issue', 'create')) process.exit(0);
  if (is('issue', 'list')) out('');
}
throw new Error('Unexpected command: ' + tool + ' ' + args.join(' '));
`);
for (const tool of ['git', 'gh']) {
  fs.writeFileSync(path.join(bin, tool), '#!/usr/bin/env bash\nexec ' + quote(posix(process.execPath)) + ' ' + quote(posix(path.join(temp, 'double.cjs'))) + ' ' + tool + ' "$@"\n');
  fs.chmodSync(path.join(bin, tool), 0o755);
}
let passed = 0;
function run(mode, expectedStatus, verify = () => {}) {
  const dir = path.join(temp, mode);
  fs.mkdirSync(dir);
  const metadata = {upstream_repository: 'NextAlone/Nagram', upstream_commit: pin,
    upstream_build: 1255, version_code_floor: mode === 'floor-exhausted' ? 2100000000 : 125001000,
    previous_release_version_code: 125000081, migration_ready: true};
  fs.writeFileSync(path.join(dir, 'nooagram-version.json'), JSON.stringify(metadata));
  fs.copyFileSync(path.join(root, 'Tools/propose-nagram-update.sh'), path.join(dir, 'propose.sh'));
  const log = path.join(dir, 'calls.jsonl');
  const env = {...process.env, SYNC_CASE: mode, SYNC_LOG: log, GITHUB_REPOSITORY: 'yuhuan17520-glitch/Nooagram', SYNC_BIN: bashPath(bin)};
  const result = spawnSync(bash, ['-c', 'export PATH="$SYNC_BIN:$PATH"; bash ./propose.sh'], {cwd: dir, env, encoding: 'utf8', timeout: 30000});
  if (result.error) throw result.error;
  if (expectedStatus === 0) assert.equal(result.status, 0, result.stderr);
  else assert.notEqual(result.status, 0, 'Expected fail-closed exit: ' + mode);
  const calls = fs.existsSync(log) ? fs.readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
  const has = (...prefix) => calls.some(call => prefix.every((v, i) => call[i] === v));
  assert.ok(!calls.some(call => call.includes('--force') || call.includes('release')), 'Must never force-push or release');
  verify({dir, metadata, calls, has});
  console.log('PASS ' + mode);
  passed++;
}
try {
  run('current', 0, ({has}) => assert.ok(!has('git', 'push')));
  run('update', 0, ({dir, has}) => {
    const meta = JSON.parse(fs.readFileSync(path.join(dir, 'nooagram-version.json')));
    assert.equal(meta.upstream_commit, target);
    assert.equal(meta.upstream_build, 1256);
    assert.equal(meta.version_code_floor, 125002000);
    assert.equal(meta.migration_ready, false);
    assert.ok(has('git', 'push', 'origin', 'HEAD:refs/heads/sync/nagram-aaaaaaaaaaaa'));
    assert.ok(has('gh', 'pr', 'create', '--base', 'ayu'));
  });
  run('remote-exists', 0, ({has}) => assert.ok(!has('git', 'remote', 'add')));
  run('branch-exists', 0, ({has}) => { assert.ok(!has('git', 'push')); assert.ok(has('gh', 'pr', 'create')); });
  run('pr-exists', 0, ({has}) => { assert.ok(!has('git', 'push')); assert.ok(!has('gh', 'pr', 'create')); });
  run('unsafe-origin', 1, ({has}) => { assert.ok(!has('git', 'fetch')); assert.ok(!has('git', 'push')); });
  run('rewritten', 1, ({has}) => assert.ok(!has('git', 'push')));
  run('conflict', 1, ({has}) => { assert.ok(has('git', 'merge', '--abort')); assert.ok(has('gh', 'issue', 'create')); assert.ok(!has('git', 'push')); });
  run('remote-error', 1, ({has}) => { assert.ok(!has('git', 'merge')); assert.ok(!has('git', 'push')); });
  run('pr-query-error', 1, ({has}) => assert.ok(!has('gh', 'pr', 'create')));
  run('floor-exhausted', 1, ({has}) => assert.ok(!has('git', 'push')));
  console.log('Sync proposal regression: ' + passed + ' passed; no real network or git writes');
} finally {
  assert.ok(path.dirname(temp) === os.tmpdir() && path.basename(temp).startsWith('nooagram-sync-'));
  fs.rmSync(temp, {recursive: true, force: true});
}
