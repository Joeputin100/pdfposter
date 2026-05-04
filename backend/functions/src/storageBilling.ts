// EXPORT NEEDED in index.ts:
//   export { dailySweep, deleteCloudCopy } from './storageBilling';
//
// RC12 — per-user batched storage billing (option 3 from the economics
// discussion). Pre-RC12 each PDF was charged 1 credit/month flat: that
// over-charged users with small posters by 13-40× the actual GCS cost.
// New model:
//
//   For each user with paid retention:
//     Sum bytes of all their billable PDFs (older than 30 days, in cloud).
//     rawCostUsd = bytes / 1024^3 × $0.026         (us-central1 storage)
//     billedUsd  = ceil(rawCostUsd × 1.5 × 100) / 100   (50% markup, ¢-rounded)
//     credits    = billedUsd × 100                  (1 credit = 1¢)
//   Charge once per 30 days per user (not per file).
//
//   Aggregate written to /users/{uid}.storageBilling for client display:
//     bytes:     total billable bytes
//     posters:   count of billable PDFs
//     lastBilledAt:    Timestamp of last bill
//     lastBilledCredits: int (credits last charged)
//     nextBillDue:      Timestamp (lastBilledAt + 30 days)
//
//   Grace period (30 days) and final delete logic unchanged.
//
//   New: 24-hour-before-deletion warning notification when balance hits 0
//   and grace expiry is within 24h.
//
// Schema (additions on top of pre-RC12):
//   /users/{uid}.storageBilling: {
//     bytes, posters, lastBilledAt, lastBilledCredits, nextBillDue
//   }
//   /users/{uid}/history/{historyId}.fileBytes: long  (size at upload time)
//   /users/{uid}/history/{historyId}.gracePeriodStartedAt is now per-user,
//   not per-file: stored on /users/{uid}.storageBilling.gracePeriodStartedAt

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { getMessaging } from 'firebase-admin/messaging';

const STORAGE_FREE_PERIOD_MS = 30 * 24 * 60 * 60 * 1000;
const STORAGE_GRACE_PERIOD_MS = 30 * 24 * 60 * 60 * 1000;
const BILLING_INTERVAL_MS = 30 * 24 * 60 * 60 * 1000;
// Firebase Cloud Storage us-central1: $0.026/GB-month. Mirror in pricing.ts
// if that ever computes a per-region rate.
const GCS_USD_PER_GB_MONTH = 0.026;
const STORAGE_MARKUP = 1.5;

interface HistoryDoc {
  cloudStorageUri?: string | null;
  createdAt?: Timestamp;
  fileBytes?: number;
}

interface StorageBilling {
  bytes?: number;
  posters?: number;
  lastBilledAt?: Timestamp;
  lastBilledCredits?: number;
  nextBillDue?: Timestamp;
  gracePeriodStartedAt?: Timestamp;
}

interface UserDoc {
  credits?: number;
  storageRetentionMode?: 'paid' | 'auto-delete';
  storageBilling?: StorageBilling;
}

/** Mirrors the Android `prettyBytes()` helper so push body and drawer row read identically. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function parseGsUri(uri: string): { bucket: string; object: string } | null {
  if (!uri.startsWith('gs://')) return null;
  const without = uri.substring('gs://'.length);
  const slash = without.indexOf('/');
  if (slash <= 0) return null;
  return { bucket: without.substring(0, slash), object: without.substring(slash + 1) };
}

/**
 * RC12b — Push the {title, body} to every FCM token registered for `uid`.
 * Best-effort: any token-level failure is logged; the only side-effect on
 * the user doc is pruning tokens that the FCM service rejects with
 * `messaging/registration-token-not-registered` (i.e. uninstalled or
 * expired tokens — leaving them in the array would balloon it forever).
 *
 * Returns the count of successfully-delivered messages so the caller can
 * decide whether to mark the originating notification doc as sent.
 */
async function sendPushToUser(
  uid: string,
  title: string,
  body: string,
): Promise<number> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const userSnap = await userRef.get();
  const tokens = (userSnap.data() as { fcmTokens?: string[] } | undefined)?.fcmTokens ?? [];
  if (tokens.length === 0) return 0;

  let delivered = 0;
  const dead: string[] = [];
  for (const token of tokens) {
    try {
      await getMessaging().send({
        token,
        notification: { title, body },
        android: {
          priority: 'high',
          notification: { channelId: 'billing' },
        },
      });
      delivered += 1;
    } catch (err: unknown) {
      const code = (err as { code?: string } | undefined)?.code ?? '';
      if (
        code === 'messaging/registration-token-not-registered' ||
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/invalid-argument'
      ) {
        dead.push(token);
      } else {
        logger.warn('FCM send failed', { uid, code, err: String(err) });
      }
    }
  }
  if (dead.length > 0) {
    try {
      await userRef.update({ fcmTokens: FieldValue.arrayRemove(...dead) });
    } catch (err) {
      logger.warn('FCM dead-token prune failed', { uid, err: String(err) });
    }
  }
  return delivered;
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

/**
 * Per-user batched billing. Returns the credits charged this run (0 if
 * not yet due, or if user moved into grace period).
 */
async function billUser(
  uid: string,
  userData: UserDoc,
  now: Timestamp,
): Promise<{ charged: number; bytes: number; posters: number; gracedEntered: boolean; deleted: number }> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const histRef = userRef.collection('history');
  const billing = userData.storageBilling ?? {};
  const mode = userData.storageRetentionMode ?? 'paid';

  // Already in grace period — check if expired or balance refilled.
  if (billing.gracePeriodStartedAt) {
    const graceAgeMs = now.toMillis() - billing.gracePeriodStartedAt.toMillis();
    const credits = Number(userData.credits ?? 0);
    if (credits > 0) {
      // Refilled — exit grace, schedule a fresh bill on next sweep.
      await userRef.update({
        'storageBilling.gracePeriodStartedAt': FieldValue.delete(),
      });
      return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted: 0 };
    }
    if (graceAgeMs >= STORAGE_GRACE_PERIOD_MS) {
      // Expired — delete all billable PDFs.
      const billable = await collectBillable(histRef, now);
      let deleted = 0;
      for (const e of billable) {
        if (e.uri) {
          await deleteBlob(e.uri);
          await histRef.doc(e.id).update({
            cloudStorageUri: FieldValue.delete(),
          });
          deleted += 1;
        }
      }
      await userRef.update({
        'storageBilling.bytes': 0,
        'storageBilling.posters': 0,
        'storageBilling.gracePeriodStartedAt': FieldValue.delete(),
        'storageBilling.lastBilledAt': FieldValue.delete(),
        'storageBilling.nextBillDue': FieldValue.delete(),
      });
      return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted };
    }
    // Still in grace, nothing to do.
    return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted: 0 };
  }

  // Auto-delete mode: wipe blobs that aged past the free period.
  if (mode === 'auto-delete') {
    const billable = await collectBillable(histRef, now);
    let deleted = 0;
    for (const e of billable) {
      if (e.uri) {
        await deleteBlob(e.uri);
        await histRef.doc(e.id).update({ cloudStorageUri: FieldValue.delete() });
        deleted += 1;
      }
    }
    await userRef.update({
      'storageBilling.bytes': 0,
      'storageBilling.posters': 0,
    });
    return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted };
  }

  // Bill if at least 30 days since last bill (or this is the first bill).
  const nextDueMs = billing.nextBillDue?.toMillis()
    ?? billing.lastBilledAt?.toMillis()
    ?? 0;
  if (nextDueMs > 0 && now.toMillis() < nextDueMs) {
    return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted: 0 };
  }

  // Sum up all billable PDFs (older than 30 days, in cloud).
  const billable = await collectBillable(histRef, now);
  if (billable.length === 0) {
    // No billable PDFs — clear aggregate, bail.
    await userRef.update({
      'storageBilling.bytes': 0,
      'storageBilling.posters': 0,
    });
    return { charged: 0, bytes: 0, posters: 0, gracedEntered: false, deleted: 0 };
  }
  const totalBytes = billable.reduce((acc, e) => acc + (e.bytes ?? 0), 0);
  const posters = billable.length;
  const rawCostUsd = (totalBytes / (1024 ** 3)) * GCS_USD_PER_GB_MONTH;
  const billedUsdCents = Math.ceil(rawCostUsd * STORAGE_MARKUP * 100);
  const credits = Math.max(billedUsdCents, 1); // floor of 1 credit if there\'s anything stored

  // Atomic charge or grace transition.
  let didCharge = false;
  let didGrace = false;
  await db.runTransaction(async (t) => {
    const fresh = await t.get(userRef);
    const userBalance = Number((fresh.data() as UserDoc | undefined)?.credits ?? 0);
    if (userBalance >= credits) {
      t.set(userRef, {
        credits: FieldValue.increment(-credits),
        storageBilling: {
          bytes: totalBytes,
          posters,
          lastBilledAt: now,
          lastBilledCredits: credits,
          nextBillDue: Timestamp.fromMillis(now.toMillis() + BILLING_INTERVAL_MS),
        },
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      t.set(userRef.collection('creditLog').doc(), {
        type: 'storage_burn',
        amount: -credits,
        bytes: totalBytes,
        posters,
        timestamp: FieldValue.serverTimestamp(),
      });
      didCharge = true;
    } else {
      t.set(userRef, {
        storageBilling: {
          bytes: totalBytes,
          posters,
          gracePeriodStartedAt: now,
        },
      }, { merge: true });
      didGrace = true;
    }
  });
  return {
    charged: didCharge ? credits : 0,
    bytes: totalBytes,
    posters,
    gracedEntered: didGrace,
    deleted: 0,
  };
}

/** Returns history entries that are past the free period AND in cloud. */
async function collectBillable(
  histRef: FirebaseFirestore.CollectionReference,
  now: Timestamp,
): Promise<Array<{ id: string; uri: string | null; bytes: number }>> {
  const snap = await histRef.where('cloudStorageUri', '!=', null).get();
  const out: Array<{ id: string; uri: string | null; bytes: number }> = [];
  for (const doc of snap.docs) {
    const h = doc.data() as HistoryDoc;
    if (!h.cloudStorageUri || !h.createdAt) continue;
    const ageMs = now.toMillis() - h.createdAt.toMillis();
    if (ageMs <= STORAGE_FREE_PERIOD_MS) continue;
    out.push({
      id: doc.id,
      uri: h.cloudStorageUri,
      bytes: h.fileBytes ?? 10 * 1024 * 1024, // 10 MB fallback when fileBytes wasn\'t recorded at upload
    });
  }
  return out;
}

export const dailySweep = onSchedule(
  { schedule: '0 3 * * *', timeZone: 'UTC', memory: '512MiB', timeoutSeconds: 540 },
  async () => {
    const db = getFirestore();
    const now = Timestamp.now();
    const usersSnap = await db.collection('users').get();
    let summary = { users: 0, charged: 0, gracedEntered: 0, deleted: 0, deletionWarnings: 0 };
    for (const userDoc of usersSnap.docs) {
      const r = await billUser(userDoc.id, userDoc.data() as UserDoc, now);
      summary.users += 1;
      summary.charged += r.charged;
      if (r.gracedEntered) summary.gracedEntered += 1;
      summary.deleted += r.deleted;

      // Write notification surface for the client AND push via FCM.
      // The Firestore doc is the durable record (in-app inbox); FCM is
      // the wake-the-device transport. We always write the doc first
      // (sent: false) and only flip to sent: true after at least one
      // FCM delivery succeeds — that way a failed push leaves a retry
      // signal rather than disappearing silently.
      const notifRef = db.collection('users').doc(userDoc.id).collection('notifications');
      let title: string | null = null;
      let body: string | null = null;
      let docRef: FirebaseFirestore.DocumentReference | null = null;

      if (r.charged > 0) {
        title = 'Storage charge';
        body = `${r.charged} ${r.charged === 1 ? 'credit' : 'credits'} charged for ${r.posters} ${r.posters === 1 ? 'poster' : 'posters'} · ${formatBytes(r.bytes)}`;
        docRef = await notifRef.add({
          type: 'storage_billed',
          credits: r.charged,
          bytes: r.bytes,
          posters: r.posters,
          createdAt: FieldValue.serverTimestamp(),
          sent: false,
        });
      } else if (r.gracedEntered) {
        title = 'Top up credits to keep your posters';
        body = `Cloud storage paused — ${r.posters} ${r.posters === 1 ? 'poster' : 'posters'} in 30-day grace period`;
        docRef = await notifRef.add({
          type: 'storage_grace_started',
          posters: r.posters,
          createdAt: FieldValue.serverTimestamp(),
          sent: false,
        });
      } else if (r.deleted > 0) {
        title = 'Cloud copies removed';
        body = `${r.deleted} ${r.deleted === 1 ? 'poster was' : 'posters were'} deleted from cloud storage`;
        docRef = await notifRef.add({
          type: 'storage_deleted',
          deletedCount: r.deleted,
          createdAt: FieldValue.serverTimestamp(),
          sent: false,
        });
      }

      if (docRef && title && body) {
        const delivered = await sendPushToUser(userDoc.id, title, body);
        if (delivered > 0) {
          await docRef.update({ sent: true, deliveredCount: delivered });
        }
      }

      // 24-hour-before-deletion warning. Triggered when user is in grace
      // period and the grace expiry is within 24-48h (we run daily, so we
      // give a 24h window to catch this once).
      const billing = (userDoc.data() as UserDoc).storageBilling;
      if (billing?.gracePeriodStartedAt) {
        const graceEndsMs = billing.gracePeriodStartedAt.toMillis() + STORAGE_GRACE_PERIOD_MS;
        const hoursUntilDelete = (graceEndsMs - now.toMillis()) / (60 * 60 * 1000);
        if (hoursUntilDelete > 0 && hoursUntilDelete <= 24) {
          const hours = Math.ceil(hoursUntilDelete);
          const posters = billing.posters ?? 0;
          const warnTitle = `Posters will be deleted in ${hours}h`;
          const warnBody = `Add credits to keep your ${posters} stored ${posters === 1 ? 'poster' : 'posters'}`;
          const warnDoc = await db.collection('users').doc(userDoc.id).collection('notifications').add({
            type: 'storage_deletion_imminent',
            hoursUntilDelete: hours,
            posters,
            createdAt: FieldValue.serverTimestamp(),
            sent: false,
          });
          const delivered = await sendPushToUser(userDoc.id, warnTitle, warnBody);
          if (delivered > 0) {
            await warnDoc.update({ sent: true, deliveredCount: delivered });
          }
          summary.deletionWarnings += 1;
        }
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
    });
    return { deleted: true };
  },
);
