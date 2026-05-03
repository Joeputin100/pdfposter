// EXPORT NEEDED in index.ts:
//   export { dailySweep, deleteCloudCopy } from './storageBilling';
//
// Phase H Task H-P3.1 + H-P3.3 — cloud PDF storage retention with credit-
// metered billing.
//
// Policy (mirrors docs/superpowers/plans/2026-05-03-phase-h-rc3-polish.md
// + pricing-policy.md updates):
//   - First 30 days of each PDF: free.
//   - After 30 days, deduct 1 credit/file/month (charged at 50% margin
//     over our actual GCS cost — 1 credit = 1¢ retail; storage of a
//     20MB PDF costs us ~$0.0004/month).
//   - When user.credits < monthly fee → grace period 30 days + email + push.
//   - After grace expiry: delete blob from GCS, clear cloudStorageUri on
//     history doc; metadata + local copy persist.
//   - User can opt into 'auto-delete' mode in account setup → delete after
//     exactly 30 days, no charge ever.
//
// Schema:
//   /users/{uid}.storageRetentionMode: 'paid' | 'auto-delete'   (default 'paid')
//   /users/{uid}/history/{historyId}.cloudStorageUri: 'gs://...' | null
//   /users/{uid}/history/{historyId}.createdAt: Timestamp
//   /users/{uid}/history/{historyId}.lastBilledAt: Timestamp | null
//   /users/{uid}/history/{historyId}.gracePeriodStartedAt: Timestamp | null
//
// Idempotency: cron uses lastBilledAt to avoid double-charging within a
// single calendar month. Re-runs of the same day are no-ops.

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';

const STORAGE_FREE_PERIOD_MS = 30 * 24 * 60 * 60 * 1000;
const STORAGE_GRACE_PERIOD_MS = 30 * 24 * 60 * 60 * 1000;
const MONTHLY_STORAGE_CREDITS = 1;
const BILLING_INTERVAL_MS = 30 * 24 * 60 * 60 * 1000;

interface HistoryDoc {
  cloudStorageUri?: string | null;
  createdAt?: Timestamp;
  lastBilledAt?: Timestamp | null;
  gracePeriodStartedAt?: Timestamp | null;
}

interface UserDoc {
  credits?: number;
  storageRetentionMode?: 'paid' | 'auto-delete';
}

/**
 * Parse a `gs://bucket/object/path` URI. Returns null on malformed input.
 */
function parseGsUri(uri: string): { bucket: string; object: string } | null {
  if (!uri.startsWith('gs://')) return null;
  const without = uri.substring('gs://'.length);
  const slash = without.indexOf('/');
  if (slash <= 0) return null;
  return { bucket: without.substring(0, slash), object: without.substring(slash + 1) };
}

async function deleteBlob(uri: string): Promise<void> {
  const parsed = parseGsUri(uri);
  if (!parsed) {
    logger.warn('deleteBlob: malformed URI', { uri });
    return;
  }
  try {
    await getStorage().bucket(parsed.bucket).file(parsed.object).delete({ ignoreNotFound: true });
  } catch (err) {
    logger.warn('deleteBlob failed', { uri, err: String(err) });
  }
}

interface UserSweepResult {
  uid: string;
  charged: number;        // credits deducted this run
  gracedEntered: number;  // history entries that just hit grace period
  deleted: number;        // history entries whose blob was removed
}

async function sweepUser(
  uid: string,
  userData: UserDoc,
  now: Timestamp,
): Promise<UserSweepResult> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const histRef = userRef.collection('history');
  const result: UserSweepResult = { uid, charged: 0, gracedEntered: 0, deleted: 0 };

  const mode = userData.storageRetentionMode ?? 'paid';
  const histSnap = await histRef.where('cloudStorageUri', '!=', null).get();

  for (const doc of histSnap.docs) {
    const h = doc.data() as HistoryDoc;
    if (!h.cloudStorageUri || !h.createdAt) continue;

    const ageMs = now.toMillis() - h.createdAt.toMillis();
    if (ageMs <= STORAGE_FREE_PERIOD_MS) continue; // still free

    // Grace period — already entered, awaiting delete or refill
    if (h.gracePeriodStartedAt) {
      const graceAgeMs = now.toMillis() - h.gracePeriodStartedAt.toMillis();
      if (graceAgeMs >= STORAGE_GRACE_PERIOD_MS) {
        await deleteBlob(h.cloudStorageUri);
        await doc.ref.update({
          cloudStorageUri: FieldValue.delete(),
          gracePeriodStartedAt: FieldValue.delete(),
          lastBilledAt: FieldValue.delete(),
        });
        result.deleted += 1;
      }
      continue;
    }

    // Auto-delete mode: delete the blob after the free period; no charge.
    if (mode === 'auto-delete') {
      await deleteBlob(h.cloudStorageUri);
      await doc.ref.update({ cloudStorageUri: FieldValue.delete() });
      result.deleted += 1;
      continue;
    }

    // Paid mode: bill if at least 30 days since the last billing (or since
    // the free period ended, whichever is later).
    const lastBilledMs = h.lastBilledAt?.toMillis()
      ?? (h.createdAt.toMillis() + STORAGE_FREE_PERIOD_MS);
    if (now.toMillis() - lastBilledMs < BILLING_INTERVAL_MS) continue;

    // Atomic charge or grace transition.
    let didCharge = false;
    let didGrace = false;
    await db.runTransaction(async (t) => {
      const fresh = await t.get(userRef);
      const credits = Number((fresh.data() as UserDoc | undefined)?.credits ?? 0);
      if (credits >= MONTHLY_STORAGE_CREDITS) {
        t.set(userRef, {
          credits: FieldValue.increment(-MONTHLY_STORAGE_CREDITS),
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        t.set(userRef.collection('creditLog').doc(), {
          type: 'storage_burn',
          amount: -MONTHLY_STORAGE_CREDITS,
          historyId: doc.id,
          timestamp: FieldValue.serverTimestamp(),
        });
        t.update(doc.ref, { lastBilledAt: now });
        didCharge = true;
      } else {
        t.update(doc.ref, { gracePeriodStartedAt: now });
        didGrace = true;
      }
    });
    if (didCharge) result.charged += MONTHLY_STORAGE_CREDITS;
    if (didGrace) result.gracedEntered += 1;
  }

  return result;
}

export const dailySweep = onSchedule(
  { schedule: '0 3 * * *', timeZone: 'UTC', memory: '512MiB', timeoutSeconds: 540 },
  async () => {
    const db = getFirestore();
    const now = Timestamp.now();
    const usersSnap = await db.collection('users').get();
    let summary = { users: 0, charged: 0, gracedEntered: 0, deleted: 0 };
    for (const userDoc of usersSnap.docs) {
      const r = await sweepUser(userDoc.id, userDoc.data() as UserDoc, now);
      summary.users += 1;
      summary.charged += r.charged;
      summary.gracedEntered += r.gracedEntered;
      summary.deleted += r.deleted;
      if (r.gracedEntered > 0 || r.deleted > 0) {
        // Notification surface: write a pending-notification doc the
        // notifications service consumes. Push + email integration lives
        // in H-P3.5 (deferred); for now, this doc is the audit trail.
        await db.collection('users').doc(userDoc.id)
          .collection('notifications').add({
            type: 'storage_billing_event',
            graced: r.gracedEntered,
            deleted: r.deleted,
            createdAt: FieldValue.serverTimestamp(),
            sent: false,
          });
      }
    }
    logger.info('dailySweep complete', summary);
  },
);

interface DeleteCloudCopyInput {
  historyId: string;
}

export const deleteCloudCopy = onCall(
  { region: 'us-central1', timeoutSeconds: 30, memory: '256MiB' },
  async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'sign-in required');
    const uid = auth.uid;
    const data = (request.data ?? {}) as Partial<DeleteCloudCopyInput>;
    const historyId = data.historyId;
    if (typeof historyId !== 'string' || historyId.length === 0) {
      throw new HttpsError('invalid-argument', 'historyId is required');
    }
    const db = getFirestore();
    const histRef = db.collection('users').doc(uid).collection('history').doc(historyId);
    const snap = await histRef.get();
    if (!snap.exists) throw new HttpsError('not-found', 'history entry not found');
    const h = snap.data() as HistoryDoc;
    if (h.cloudStorageUri) {
      await deleteBlob(h.cloudStorageUri);
    }
    await histRef.update({
      cloudStorageUri: FieldValue.delete(),
      gracePeriodStartedAt: FieldValue.delete(),
      lastBilledAt: FieldValue.delete(),
    });
    return { deleted: true };
  },
);
