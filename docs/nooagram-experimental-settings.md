# Experimental settings and product branding

By default, Nooagram displays all experimental settings when opening the page.
Existing `ShowHiddenFeature` preferences no longer control row visibility; no
preference migration is required. The legacy key remains readable for backup
compatibility. Long-pressing the version retains the debug menu but no longer
toggles this legacy flag or displays a deliberately misleading error toast.

Visibility does not enable an option. In particular, upload/download acceleration,
local premium presentation, ghost mode, filters, and story choices keep their
saved values. The sensitive-content control still uses Telegram account settings
and respects the server-provided `sensitive_can_change` capability.

Product-owned settings titles, notifications, version information, and preview
content use Nooagram. String resource identifiers, package names, backup keys,
protocol formats, and upstream attribution remain compatible. An explicit
existing save directory is retained; new default destinations use Nooagram.

The settings regression runner executes the actual page-rebuild method with
both values of the old hidden flag and both channel-icon choices. It checks
that experimental rows remain present, the dependent channel-label row does
not duplicate across rebuilds, and no saved feature choice is written. Run:

```powershell
./Tools/pinned-settings-regression/run.ps1
```

Validation on 2026-10-23:

- Settings/pinned regression probes: 26 passed, 0 failed.
- `nooagram-experimental-branding-verified-build.log` records
  `:TMessagesProj:testDebugUnitTest` and `BUILD SUCCESSFUL`. The three
  `testDebugUnitTest` result files contain 12 tests with 0 failures, 0 errors,
  and 0 skipped: 1 `AyuCustomSavePathTest`, 4 `AyuLocalizationTest`, and 7
  `ProductBrandingLocalizationTest` tests. Branding coverage includes stale
  cloud strings in English, simplified and traditional Chinese, Polish, and
  Indonesian. An additional Arabic push-service test covers the old translated
  Nagram brand discovered and corrected during the independent DS review.
- The APK signature was verified and matched the embedded native library.
- Updated the independent `fork.yuhuan.nooagram.migrationtest` phone installation
  with `adb install -r`; production installation and data were not modified.
- On-device XML checks completed after unlocking. The settings page showed
  `Nooagram设置`, `上游频道`, and `上游技巧频道`; the experimental page showed
  `实验性功能` and had `加速上传和下载` enabled (`true`). Reopening the page
  showed the same enabled state.
- After entering the experimental page and returning, the main session
  comparison found the saved `ShowHiddenFeature` and `enhancedFileLoader` values
  unchanged. No fatal exception appeared in the new process log.
- A further force-stop and cold start of the independent test app completed
  successfully; the Nooagram settings entry remained correct and the new
  process log contained no fatal exception markers.
- A second on-device check temporarily set the legacy hidden flag to false in
  the independent test app. Upload/download acceleration, sensitive-content
  filtering, and local premium rows remained visible. The app normalized the
  false flag to its absent/default-false state. The original preference file was
  restored afterward, with `ShowHiddenFeature=true` and
  `enhancedFileLoader=true` confirmed.
- This build has not been published as a formal release.
