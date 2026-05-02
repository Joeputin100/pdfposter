// EXPORT NEEDED in index.ts:
//   export { getFalBalance } from './balance';
//
// Admin-gated callable that queries FAL's account billing endpoint.
// FAL docs: https://fal.ai/docs/platform-apis/v1/account/billing
// Same FAL_KEY secret used by pricing.ts and upscale.ts; the docs describe
// this as an "Admin API key" so the existing key should suffice.

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { defineSecret } from 'firebase-functions/params';
import axios from 'axios';

const FAL_KEY = defineSecret('FAL_KEY');
const FAL_BILLING_URL = 'https://api.fal.ai/v1/account/billing';

interface FalBillingResponse {
  username: string;
  credits?: {
    current_balance: number;
    currency: string;
  };
}

export const getFalBalance = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 30,
    memory: '256MiB',
    secrets: [FAL_KEY],
  },
  async (request) => {
    const auth = request.auth;
    if (!auth) {
      throw new HttpsError('unauthenticated', 'sign-in required');
    }
    // Custom-claim gate. Bootstrap with a one-shot admin script:
    //   await getAuth().setCustomUserClaims(uid, { admin: true });
    // Until that runs, every call returns permission-denied — safe default.
    const isAdmin = (auth.token as { admin?: boolean }).admin === true;
    if (!isAdmin) {
      throw new HttpsError('permission-denied', 'admin only');
    }

    const resp = await axios.get<FalBillingResponse>(FAL_BILLING_URL, {
      params: { expand: 'credits' },
      headers: { Authorization: `Key ${FAL_KEY.value()}` },
      timeout: 15_000,
      validateStatus: () => true,
    });
    if (resp.status >= 400) {
      logger.error('FAL billing API error', {
        status: resp.status,
        body: resp.data,
      });
      throw new HttpsError('unavailable', `FAL billing API ${resp.status}`);
    }
    const credits = resp.data.credits;
    if (!credits) {
      logger.error('FAL billing response missing credits block', { body: resp.data });
      throw new HttpsError('internal', 'FAL billing response missing credits block');
    }

    const result = {
      username: resp.data.username,
      balance: credits.current_balance,
      currency: credits.currency,
      fetchedAt: new Date().toISOString(),
    };
    // Log every successful read so Cloud Logging gives ops a passive
    // balance history without a separate Firestore doc.
    logger.info('FAL balance', result);
    return result;
  },
);
