# Contributing to CCWEAROS

Issues and PRs welcome. This is a small project, kept intentionally surface-area-small so it stays understandable.

## Before opening a PR

1. **Read [`CLAUDE.md`](CLAUDE.md)** — the project layout, RTDB schema, and hard rules live there. Most "why is this written this way?" questions have answers in there.
2. **Skim [`docs/CHANGELOG.md`](docs/CHANGELOG.md)** for the relevant sprint. The history is dense but very specific about why decisions were made.
3. **Look at [Design choices worth knowing about](README.md#design-choices-worth-knowing-about)** in the README — covers the non-obvious gotchas (node-pty +x bit, `AnimatedContent` content keys, `busy` flag reset order, etc.).

## Running locally

See the [Setup section in the README](README.md#setup). Five steps from clone to a running daemon + watch.

For development cycles:

```bash
# Wrapper — typecheck + unit tests on every change
cd wrapper
npm run typecheck
npm test

# Watch — emulator screenshot loop
cd ../watch
./gradlew assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am force-stop com.caamano.ccwearos
adb -s emulator-5554 shell am start -n com.caamano.ccwearos/.presentation.MainActivity
```

`wrapper/scripts/demo-loop.ts` drives realistic RTDB state transitions if you want to test watch UI without a real Claude session.

## What to send

- **Bug reports**: include the relevant 10-20 lines of `~/Library/Logs/ccwearos.log` and an `adb logcat` filtered to `com.caamano.ccwearos`. Mention emulator vs real watch and the exact reproduction sequence.
- **Feature PRs**: keep them focused. If a change touches the RTDB schema, update both `wrapper/src/types/schema.ts` AND `watch/.../data/RtdbModels.kt` in the same PR — they're a mirror.
- **UI changes**: include a before/after screenshot from the Wear_OS_Large_Round emulator at minimum. Bonus points for a Galaxy Watch 8 capture if you have one.
- **Documentation PRs**: very welcome. README and CLAUDE.md drift the fastest.

## Things that won't get merged

- Anything that commits a `google-services.json`, `firebase-admin-key.json`, `.env`, or your watch's UID into a sample/test file.
- Anything that introduces a non-MIT-compatible dependency.
- Anything that bypasses the `--no-verify` hook lock at commit time (project uses `simple-git-hooks` for a reason).

## Style

- Wrapper code: TypeScript strict, no `any`, no `as` casts that aren't safe-by-construction.
- Watch code: Compose Material3 conventions, `sp` for text (not `dp`), `Modifier.semantics` for any custom icons.
- Commit messages: conventional commits (`feat(scope):`, `fix(scope):`, `docs(scope):`).
- One logical change per commit.

## License

By contributing, you agree your contributions will be licensed under MIT (same as the repo).
