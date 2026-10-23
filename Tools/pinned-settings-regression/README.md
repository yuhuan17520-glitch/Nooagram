# Pinned Hider and Settings Regression Probes

Run from the repository root with JDK 21:

```powershell
& 'Tools/pinned-settings-regression/run.ps1'
```

This runner does not use Gradle, network access, device state, or existing app
build outputs. Generated classes go to `build/pinned-settings-regression`.

The runner compiles the actual `NooagramPinnedHider` and `ConfigItem`. It uses
the JDK parser to extract the current activity row-click, observer lifecycle,
settings-import, and filter-notification method bodies. Android preferences,
account identities, UI scheduling, dialogs, and notifications use in-memory
doubles. The JSON double supports the integer arrays used by these fixtures.

Coverage includes quarantine of unknown legacy lists for active and inactive
slots, precedence of existing user data, preservation of an existing quarantine,
repeated migration, slot reuse after logout, and identity movement across slots.
The extracted restore-dialog method previews legacy titles, preserves quarantine
when cancelled, merges only after confirmation, and rejects confirmation after
the account identity changes. Cache-reset checks verify persisted restore state;
logged-out restores retain the quarantine for later review.

Additional checks cover idempotent hide/restore, bulk toggles, defensive list
copies, logged-out writes, account-scoped observers, stale queued events, and
bound view tags when row positions have changed. Import probes execute the
confirmation callbacks and verify persistence before exactly one callback, typed values, cancellation,
invalid input, and reserved characters after decoding. Filter-notification
probes verify all activated accounts receive updates regardless of selection.

Startup/logout ordering, row binding, chat event subscription, and the URI
encoding/decoding API pairing are source checks. These do not simulate Android
view rendering, disk durability, or execute Android's URI implementation.

## Review Result

2026-09-28: all 25 probes passed against the quarantined-migration implementation.
The separate `TMessagesProj/src/test/run-filter-regressions.ps1` runner also
passed all 27 JUnit tests, including account/session cache separation, album
invalidation, and the non-null quick-filter error fallback.

The R32 fixture reproduces this pre-upgrade state:

1. On the old app, A hides a dialog in slot 0, logs out, and B reuses slot 0.
2. At first startup of the fixed app, `hidden_pinned_0` still contains A's list.
3. At migration, the data must move to `legacy_hidden_pinned_0`, without creating
   or modifying `hidden_pinned_user_<B>`.

The old format has no ownership evidence. Updated expectations require that no
legacy slot list is automatically assigned to any login, including the user
currently occupying that slot. Existing lists remain untouched until an explicit
reviewed restore merges the quarantine into the confirmed user's list. The
runner returns nonzero for any violated expectation. No hider or settings
production code was changed by this review.
