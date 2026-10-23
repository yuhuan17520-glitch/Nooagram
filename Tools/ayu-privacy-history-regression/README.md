# Ayu Privacy and History Regression Checks

Run from the repository root with JDK 21 or newer:

```powershell
.\tools\ayu-privacy-history-regression\run.ps1
```

The runner uses an existing SQLite JDBC jar from the local Gradle dependency
cache. An explicit local jar can be passed with `-SqliteJdbc`. It never starts
Gradle or downloads anything. Generated classes and synthetic fixture databases
are written under `build/ayu-privacy-history-regression`.

`RegressionRunner.java` compiles the actual production `AyuState`,
`AyuStateVariable`, `AyuGhostUtils`, `AyuGhostConfig`, `AyuGhostController`,
`TLRPCWrappedBypass`, `AyuData`, `LockedDao`, `AyuDataLock`, and
`AyuDatabaseMerger` sources against offline Android/Telegram/Room doubles.
The merger executes its production SQL against a real local SQLite database.
History constructor, query, and observer methods are extracted using javac
source positions and executed with a minimal fragment double. The full history
view is not compiled by this harness.

Covered behaviors:

- R02: conflicting foreground/background account policy, channel/content reads,
  and force-allow/force-block exceptions.
- R03: both wrapped manual-read overloads leave subsequent reads blocked for
  the same dialog, another dialog, and another account. Manual and queued
  read-after-send callbacks retain their original account after selection changes.
- R21: all 32 master-toggle, online-value, online-lock, offline-value, and
  offline-lock combinations, plus shared global settings.
- R06: message-account binding before the first history query, correct row
  identity, observer registration/removal after account switches, and rejection
  of another account's notifications.
- R22: actual file-copy errors on the first backup file and after a partial
  backup, unchanged live database/WAL bytes, reopened DAO access, validation
  failure rollback, and successful replacement.
- R23: same-second text, entities, document, reply, markup, rich-message,
  account, and media-path differences; null versus empty blobs; exact duplicate
  rows; repeat import; and older source schemas missing target columns.

Recorded result on 2026-09-28: **10 cases, 302 assertions passed**.

The Room, notification, network, and worker scheduling dependencies are doubles;
these checks do not claim Android lifecycle, native SQLite/Room migration, real
network, or device coverage.

The removed `setAllowReadPacket` API previously had three external callers:
one TTL action in `ChatActivity` and two resets in `GhostModeActivity`. Main's
integration removed those calls and carries explicit TTL authorization to the
concrete `readMessageContents` request in `MessagesController`. A final source
inventory found no remaining `setAllowReadPacket`, `getAllowReadPacket`, or
`allowReadPacket` references under `TMessagesProj/src`. These external call-site
changes are owned by main and are not compiled by this focused harness.
