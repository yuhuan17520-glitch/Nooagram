# Pinned Hider and Settings Regression Probes

Run from the repository root with JDK 21:

```powershell
& 'Tools/pinned-settings-regression/run.ps1'
```

This runner does not use Gradle, network access, device state, or existing app
build outputs. Generated classes go to `build/pinned-settings-regression`.

The profile-avatar probe executes the production refresh method against cold
controller caches, persisted self profiles, new photos, account switches, and
missing users. It checks that the image receiver uses the requested account and
that the fallback drawable is initialized. The settings-link probe also checks
the shared download page and links from the former AyuMoments/general rows.
It also verifies the `ghostmode`, `ayuspy`, and `regexfilters` routes resolve to
their respective pages and dispatch both row navigation and value imports.

The collapsed-stories title probe executes the production placement method with
centering enabled and disabled, an emoji status, a subtitle, long text, and a
scaled title. It checks horizontal coordinates and clipping width with view
doubles; Android view measurement is covered separately by
`ActionBarCenterTitleTest`, which also checks the actual StaticLayout text
coordinates, empty status decoration, status removal, and connection overlays.
The home-title source check requires the saved
centering preference instead of a forced left alignment.

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
The experimental-page probe executes the current page-rebuild method with both
legacy hidden-feature values and channel-icon choices. All experimental options
remain visible, the dependent channel-label row appears exactly once when needed,
and rebuilding the page must not write any saved option or ghost-mode choice.
The color picker probe compiles the production touch handler and verifies slop,
vertical cancellation, leave-and-return cancellation, horizontal-to-vertical
cancellation, and ACTION_UP-only selection. Deep-link and restore probes compile
the current settings router and LaunchActivity factory, including the distinct
`com.radolyn` and `tw.nekomimi` settings base types.
The branding source check verifies that the dialogs title and stories header use
the normal `Nooagram` text/status path without the legacy Nagram wordmark, while
the custom title and user-name title preferences remain the title inputs.

Startup/logout ordering, row binding, chat event subscription, and the URI
encoding/decoding API pairing are source checks. These do not simulate Android
view rendering, disk durability, or execute Android's URI implementation.

## Review Result

2026-10-31: revalidated against the updated production APIs with JDK 21:
**30 passed, 0 failed**, plus PASS results for the pinned-menu, profile-avatar,
and collapsed-stories probes; exit code 0.

2026-10-23: all 30 probes passed, including the color picker, AyuMoments deep
link, custom-settings process restoration, and home branding checks.
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
