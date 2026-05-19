---
description: Migrate this Claude session to a new Terminal under `cc` so the watch becomes the sole permission gate (no Terminal double-confirm)
allowed-tools: Bash(/Users/luismiguelcaamano/projects/CCWEAROS/wrapper/node_modules/.bin/tsx:*)
---

!`/Users/luismiguelcaamano/projects/CCWEAROS/wrapper/node_modules/.bin/tsx /Users/luismiguelcaamano/projects/CCWEAROS/wrapper/scripts/hooks/enable-takeover.ts`

Report the output above to the user verbatim. If you see "✓ Sesión migrada" a
new Terminal window has opened with `cc --resume <id>` and the watch will be
the sole permission gate from there on. This window can be closed when the
user wants — the conversation continues in the new window.
