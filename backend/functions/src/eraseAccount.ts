// rc82: real server-side account erasure behind the Manage Account DANGER
// ZONE (type-CANCEL confirm). Until now the client stub only signed out
// locally (MainActivity onEraseAccount comment: "Backend wipe lands in a
// follow-up RC") — a Play-policy problem once the data-safety form promises
// working deletion, and the posterpdf.web.app/delete-account page documents
// this same scope.
//
// Erasure scope (mirrors the privacy policy):
//   DELETED:  users/{uid} doc + every subcollection (creditLog, history,
//             notifications, quota), Storage files under users/{uid}/**,
//             and the Firebase Auth user record itself.
//   RETAINED: creditLog entries are archived to erasedAccounts/{uid}/
//             creditLog first — transaction records kept for billing
//             reconciliation / abuse prevention, per policy §5. Legacy
//             top-level upscaleTransactions docs are likewise retained.
//             No email/display-name is kept — the archive is keyed by the
//             (now meaningless) uid only.
//
// Idempotent: every step tolerates already-deleted state, so a client
// retry after a partial failure converges to fully-erased.

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { getAuth } from 'firebase-admin/auth';

export const eraseAccount = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 300,
    memory: '256MiB',
  },
  async (request) => {
    const auth = request.auth;
    if (!auth) {
      throw new HttpsError('unauthenticated', 'sign-in required');
    }
    const uid = auth.uid;
    const db = getFirestore();
    const userRef = db.collection('users').doc(uid);

    try {
      // 1. Archive the credit ledger (retention per privacy policy §5).
      const ledger = await userRef.collection('creditLog').get();
      if (!ledger.empty) {
        const archiveRoot = db.collection('erasedAccounts').doc(uid);
        let batch = db.batch();
        let inBatch = 0;
        batch.set(archiveRoot, {
          erasedAt: FieldValue.serverTimestamp(),
          ledgerEntries: ledger.size,
        }, { merge: true });
        inBatch += 1;
        for (const doc of ledger.docs) {
          batch.set(archiveRoot.collection('creditLog').doc(doc.id), doc.data());
          inBatch += 1;
          if (inBatch >= 450) {
            await batch.commit();
            batch = db.batch();
            inBatch = 0;
          }
        }
        if (inBatch > 0) await batch.commit();
      }

      // 2. Storage: everything under the user's private prefixes. rc84 fix:
      // upscale OUTPUTS live under upscaled/{uid}/ (see upscale.ts
      // downloadAndStoreOutput) — deleting only users/{uid}/ left erased
      // accounts' images behind.
      await getStorage().bucket().deleteFiles({ prefix: `users/${uid}/`, force: true });
      await getStorage().bucket().deleteFiles({ prefix: `upscaled/${uid}/`, force: true });

      // 3. Firestore: user doc + all subcollections.
      await db.recursiveDelete(userRef);

      // 4. Auth record last, so a mid-flight failure leaves the user able
      //    to sign in again and retry.
      await getAuth().deleteUser(uid).catch((err: { code?: string }) => {
        if (err?.code !== 'auth/user-not-found') throw err;
      });

      logger.info('eraseAccount completed', { uid, archivedLedger: ledger.size });
      return { ok: true };
    } catch (err) {
      logger.error('eraseAccount failed', { uid, err: String(err) });
      throw new HttpsError('internal', 'erase failed — please retry');
    }
  },
);
