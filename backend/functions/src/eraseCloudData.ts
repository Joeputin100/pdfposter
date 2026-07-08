// rc84: partial data deletion — wipes the user's CLOUD CONTENT while keeping
// the account, credits, and legally-retained transaction records. Powers the
// self-service page at posterpdf.web.app/delete-data (Play data-safety
// "delete data without deleting your account" URL) and is callable from the
// app later. Signed-in web users invoke it directly after Google sign-in —
// no email round-trip.
//
// Deletes: Storage users/{uid}/** and upscaled/{uid}/**, the history and
// notifications subcollections, and the user doc's storage-billing state.
// Keeps: the auth record, users/{uid} doc (credits!), creditLog, quota,
// and top-level upscaleTransactions rows (billing/abuse records — their
// output files are deleted above, the records themselves are retained per
// privacy policy §5).

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';

export const eraseCloudData = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 300,
    memory: '256MiB',
  },
  async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'sign-in required');
    if ((auth.token.firebase as { sign_in_provider?: string })?.sign_in_provider === 'anonymous') {
      throw new HttpsError('permission-denied', 'sign in with Google to manage cloud data');
    }
    const uid = auth.uid;
    const db = getFirestore();

    try {
      await getStorage().bucket().deleteFiles({ prefix: `users/${uid}/`, force: true });
      await getStorage().bucket().deleteFiles({ prefix: `upscaled/${uid}/`, force: true });

      const userRef = db.collection('users').doc(uid);
      await db.recursiveDelete(userRef.collection('history'));
      await db.recursiveDelete(userRef.collection('notifications'));
      await userRef.set(
        { storageBilling: FieldValue.delete() },
        { merge: true },
      ).catch(() => { /* user doc may not exist for never-synced accounts */ });

      logger.info('eraseCloudData complete', { uid });
      return { ok: true };
    } catch (err) {
      logger.error('eraseCloudData failed', { uid, err: String(err) });
      throw new HttpsError('internal', 'cloud data deletion failed — please retry');
    }
  },
);
