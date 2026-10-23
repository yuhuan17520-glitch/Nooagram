param([string]$ArtifactDirectory)
# Retained only to fail old build instructions safely. A matching source revision
# and SHA256 do not make upstream signer-bound libraries usable in this fork.
throw 'Official Nagram native binaries require the upstream APK certificate. Do not repackage them. Set COMPILE_NATIVE=1 and build native sources for this APK signing certificate; see docs/nagram-migration.md.'
