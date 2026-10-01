// Conditional clearing of the watch's permission prompt.
//
// Why conditional: answering prompt N writes to the pty, and Claude can emit
// prompt N+1 (a new publishPermissionPrompt with a new id) before our clear
// of N lands. A blind setPermissionPrompt(null) would then wipe N+1 on RTDB
// while the local ActivePrompt holds N+1 — the watch never sees it and
// Claude hangs. So we clear only if /permissionPromptId is still N.
//
// Two steps, because RTDB has no multi-path compare-and-set:
//   1. Transaction on /permissionPromptId: null it iff it still equals `id`.
//   2. If that committed AND no newer publish was issued locally since we
//      consumed N (`superseded()`), null /permissionPrompt.
// Step 2 is safe because writes from one client are applied in order: a
// publish issued after our text-null lands after it; a publish issued
// before is caught by `superseded()` (its multi-path update rewrites the
// text anyway).

import { db } from "./firebase.js";

export interface PromptStoreBackend {
  // Null /permissionPromptId iff it currently equals `expected`. Resolves to
  // whether the clear committed.
  casClearId: (expected: string) => Promise<boolean>;
  // Null /permissionPrompt (the visible text).
  clearText: () => Promise<void>;
}

export async function clearPromptIf(
  backend: PromptStoreBackend,
  id: string,
  superseded: () => boolean = () => false,
): Promise<boolean> {
  const committed = await backend.casClearId(id);
  if (!committed) return false;
  if (superseded()) return false;
  await backend.clearText();
  return true;
}

export const firebasePromptBackend: PromptStoreBackend = {
  casClearId: async (expected) => {
    const res = await db()
      .ref("/permissionPromptId")
      .transaction((cur: string | null) =>
        // `null` on a cold cache makes the server re-run us with the real
        // value; returning null there is a harmless no-op write.
        cur === expected ? null : cur === null ? null : undefined,
      );
    // Committed with a null snapshot either means we cleared our id or the
    // path was already empty — both mean "no other prompt is active".
    return res.committed && res.snapshot.val() === null;
  },
  clearText: async () => {
    await db().ref("/permissionPrompt").set(null);
  },
};

// Clear /permissionPrompt + /permissionPromptId only if the active id is
// still `id`. See the header for `superseded`.
export function clearPermissionPromptIf(
  id: string,
  superseded: () => boolean = () => false,
): Promise<boolean> {
  return clearPromptIf(firebasePromptBackend, id, superseded);
}
