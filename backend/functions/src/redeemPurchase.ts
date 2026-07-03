// rc82: server-authoritative Play Billing redemption — closes the 2026-05-30
// review's billing criticals (BillingRepository.kt:276-293 client-side grant
// stub; :251-263 grant-before-consume replay). The client flow is:
//   purchase → redeemPurchase (this callable) → consume
// and on app start every still-unconsumed token is re-submitted here, so
// idempotency on purchaseToken is the linchpin: a token grants credits
// exactly once, ever, no matter how many times it is presented.
//
// Signature verification: Play signs the purchase JSON with the app's RSA
// licensing key (SHA1withRSA — Play's algorithm, unchanged since 2012).
// The Base64 public key lives in Play Console → Monetize → Monetization
// setup, stored here as the PLAY_LICENSE_PUBKEY secret. Until that secret
// holds a real key (placeholder 'UNSET'), redemptions are allowed but
// recorded with verified:false — acceptable only pre-production while
// license-tester purchases flow; the key MUST be set before public launch.
//
//   gcloud secrets create PLAY_LICENSE_PUBKEY --replication-policy=automatic
//   echo -n "<base64-key-from-play-console>" | \
//     gcloud secrets versions add PLAY_LICENSE_PUBKEY --data-file=-

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { defineSecret } from 'firebase-functions/params';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import * as crypto from 'crypto';
import { SKUS } from './pricing.js';

const PLAY_LICENSE_PUBKEY = defineSecret('PLAY_LICENSE_PUBKEY');
const EXPECTED_PACKAGE = 'com.posterpdf';

function pemFromBase64(b64: string): string {
  const lines = b64.replace(/\s+/g, '').match(/.{1,64}/g) ?? [];
  return `-----BEGIN PUBLIC KEY-----\n${lines.join('\n')}\n-----END PUBLIC KEY-----\n`;
}

function verifyPlaySignature(purchaseJson: string, signatureB64: string, keyB64: string): boolean {
  try {
    const verifier = crypto.createVerify('RSA-SHA1');
    verifier.update(purchaseJson, 'utf8');
    return verifier.verify(pemFromBase64(keyB64), signatureB64, 'base64');
  } catch (err) {
    logger.error('signature verification threw', { err: String(err) });
    return false;
  }
}

interface RedeemInput {
  purchaseToken?: string;
  productId?: string;
  purchaseJson?: string;
  signature?: string;
}

export const redeemPurchase = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 30,
    memory: '256MiB',
    secrets: [PLAY_LICENSE_PUBKEY],
  },
  async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'sign-in required');
    if ((auth.token.firebase as { sign_in_provider?: string })?.sign_in_provider === 'anonymous') {
      throw new HttpsError('permission-denied', 'anonymous users cannot redeem purchases');
    }
    const uid = auth.uid;

    const data = (request.data ?? {}) as RedeemInput;
    const { purchaseToken, productId, purchaseJson, signature } = data;
    if (!purchaseToken || !productId || !purchaseJson || !signature) {
      throw new HttpsError(
        'invalid-argument',
        'purchaseToken, productId, purchaseJson, and signature are all required',
      );
    }

    const sku = SKUS.find((s) => s.id === productId);
    if (!sku) throw new HttpsError('invalid-argument', `unknown productId: ${productId}`);
    const credits = sku.baseCredits + sku.bonusCredits;

    // Cross-check the signed JSON against the loose params — the signature
    // covers purchaseJson, so these fields are the trustworthy copies.
    let parsed: {
      purchaseToken?: string; productId?: string;
      packageName?: string; purchaseState?: number;
    };
    try {
      parsed = JSON.parse(purchaseJson);
    } catch {
      throw new HttpsError('invalid-argument', 'purchaseJson is not valid JSON');
    }
    if (parsed.purchaseToken !== purchaseToken
        || parsed.productId !== productId
        || parsed.packageName !== EXPECTED_PACKAGE
        || parsed.purchaseState !== 0 /* PURCHASED */) {
      throw new HttpsError('invalid-argument', 'purchaseJson fields do not match the request');
    }

    const licenseKey = PLAY_LICENSE_PUBKEY.value().trim();
    let verified = false;
    if (licenseKey && licenseKey !== 'UNSET') {
      verified = verifyPlaySignature(purchaseJson, signature, licenseKey);
      if (!verified) {
        logger.warn('redeemPurchase: BAD SIGNATURE', { uid, productId });
        throw new HttpsError('permission-denied', 'purchase signature invalid');
      }
    } else {
      // Pre-launch grace: license key not yet pasted from Play Console.
      logger.warn('redeemPurchase: PLAY_LICENSE_PUBKEY unset — granting UNVERIFIED', {
        uid, productId,
      });
    }

    const db = getFirestore();
    const redeemRef = db.collection('redeemedPurchases').doc(purchaseToken);
    const userRef = db.collection('users').doc(uid);

    const balance = await db.runTransaction(async (t) => {
      const [redeemSnap, userSnap] = await Promise.all([t.get(redeemRef), t.get(userRef)]);
      const current = (userSnap.data()?.credits as number | undefined) ?? 0;
      if (redeemSnap.exists) {
        // Idempotent replay — the token already granted. Same-user replays
        // are the expected failed-consume recovery; cross-user replays are
        // an attack (token sniffed from another account) and get rejected.
        if (redeemSnap.data()?.uid !== uid) {
          throw new HttpsError('permission-denied', 'purchase already redeemed by another account');
        }
        return current;
      }
      t.set(userRef, { credits: FieldValue.increment(credits) }, { merge: true });
      t.set(userRef.collection('creditLog').doc(), {
        type: 'purchase',
        productId,
        credits,
        purchaseToken,
        verified,
        at: FieldValue.serverTimestamp(),
      });
      t.set(redeemRef, {
        uid,
        productId,
        credits,
        verified,
        redeemedAt: FieldValue.serverTimestamp(),
      });
      return current + credits;
    });

    logger.info('redeemPurchase complete', { uid, productId, credits, verified });
    return { balance };
  },
);
