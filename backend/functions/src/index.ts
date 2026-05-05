import * as admin from "firebase-admin"
import { onRequest } from "firebase-functions/v2/https"
import express, { Request, Response } from "express"
import cors from "cors"
import { sendPushToUser, formatBytes } from "./storageBilling"

admin.initializeApp()

const db = admin.firestore()
const app = express()
app.use(cors({ origin: true }))
app.use(express.json())

type AuthedRequest = Request & { uid?: string }

const FREE_UPSCALES_LIMIT = 3

function falBasePriceUsdForOutputMp(outputMp: number): number {
  // From fal-ai/topaz/upscale/image pricing table (single image)
  if (outputMp <= 24) return 0.08
  if (outputMp <= 48) return 0.16
  if (outputMp <= 96) return 0.32
  // linear interpolate up to 512 MP at 1.36
  const clamped = Math.min(outputMp, 512)
  const t = (clamped - 96) / (512 - 96)
  return 0.32 + t * (1.36 - 0.32)
}

function creditCostForOutputMp(outputMp: number): number {
  if (outputMp <= 24) return 1
  if (outputMp <= 48) return 2
  if (outputMp <= 96) return 4
  return 8
}

async function requireAuth(req: AuthedRequest, res: Response): Promise<boolean> {
  const auth = req.headers.authorization ?? ""
  if (!auth.startsWith("Bearer ")) {
    res.status(401).json({ error: "Missing bearer token" })
    return false
  }
  const token = auth.substring("Bearer ".length)
  try {
    const decoded = await admin.auth().verifyIdToken(token)
    req.uid = decoded.uid
    return true
  } catch {
    res.status(401).json({ error: "Invalid token" })
    return false
  }
}

async function getOrCreateUser(uid: string) {
  const ref = db.collection("users").doc(uid)
  const snap = await ref.get()
  if (!snap.exists) {
    await ref.set({
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      paidCredits: 0,
      stagedPaidCredits: 0,
      freeUpscalesUsed: 0,
      nagDismissed: false,
      supportPurchaseActive: false,
    })
    return (await ref.get()).data()!
  }
  return snap.data()!
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "pdfposter-backend", phase: 2 })
})

app.post("/v1/upscale/start", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const hasFalKey = !!process.env.FAL_KEY
  if (!hasFalKey) {
    res.status(500).json({ error: "FAL_KEY secret not configured" })
    return
  }
  res.status(501).json({
    error: "not_implemented",
    message: "Upscale execution endpoint will be implemented in next phase. Use quote + stage endpoints now.",
  })
})

app.post("/v1/bootstrap", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const user = await getOrCreateUser(uid)
  const freeRemaining = Math.max(0, FREE_UPSCALES_LIMIT - (user.freeUpscalesUsed ?? 0))
  const availablePaid = Math.max(0, (user.paidCredits ?? 0) - (user.stagedPaidCredits ?? 0))
  res.json({
    uid,
    freeUpscalesRemaining: freeRemaining,
    paidCreditsAvailable: availablePaid,
    nagDismissed: !!user.nagDismissed,
    supportPurchaseActive: !!user.supportPurchaseActive,
  })
})

app.post("/v1/credits/quote", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const outputMegapixels = Number(req.body?.outputMegapixels ?? 24)
  const safeMp = Number.isFinite(outputMegapixels) ? Math.max(1, outputMegapixels) : 24
  const credits = creditCostForOutputMp(safeMp)
  const falBaseUsd = falBasePriceUsdForOutputMp(safeMp)
  const chargedUsd = falBaseUsd * 2.0 // 100% markup
  res.json({
    outputMegapixels: safeMp,
    credits,
    falBaseUsd,
    chargedUsd,
    note: "Prices are estimated from current tier model and may be adjusted by backend config.",
  })
})

app.post("/v1/credits/stage", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const outputMegapixels = Number(req.body?.outputMegapixels ?? 24)
  const sourceHash = String(req.body?.sourceHash ?? "")
  if (!sourceHash) {
    res.status(400).json({ error: "sourceHash is required" })
    return
  }
  const creditsNeeded = creditCostForOutputMp(Math.max(1, outputMegapixels))
  const txRef = db.collection("upscaleTransactions").doc()

  await db.runTransaction(async (tx) => {
    const userRef = db.collection("users").doc(uid)
    const userSnap = await tx.get(userRef)
    const user = userSnap.exists ? userSnap.data()! : {
      paidCredits: 0,
      stagedPaidCredits: 0,
      freeUpscalesUsed: 0,
    }

    const freeUsed = user.freeUpscalesUsed ?? 0
    const paid = user.paidCredits ?? 0
    const staged = user.stagedPaidCredits ?? 0
    const freeRemaining = Math.max(0, FREE_UPSCALES_LIMIT - freeUsed)

    if (freeRemaining > 0) {
      tx.set(userRef, {
        freeUpscalesUsed: freeUsed + 1,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, { merge: true })
      tx.set(txRef, {
        uid,
        sourceHash,
        mode: "free",
        credits: 0,
        status: "staged",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      })
      return
    }

    const availablePaid = Math.max(0, paid - staged)
    if (availablePaid < creditsNeeded) {
      throw new Error("INSUFFICIENT_CREDITS")
    }

    tx.set(userRef, {
      stagedPaidCredits: staged + creditsNeeded,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true })
    tx.set(txRef, {
      uid,
      sourceHash,
      mode: "paid",
      credits: creditsNeeded,
      status: "staged",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    })
  }).catch((err: Error) => {
    if (err.message === "INSUFFICIENT_CREDITS") {
      res.status(402).json({ error: "Insufficient credits" })
      return
    }
    res.status(500).json({ error: "Failed to stage credits" })
  })

  if (!res.headersSent) {
    res.json({ transactionId: txRef.id, status: "staged" })
  }
})

app.post("/v1/credits/commit", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const transactionId = String(req.body?.transactionId ?? "")
  if (!transactionId) {
    res.status(400).json({ error: "transactionId is required" })
    return
  }
  const txRef = db.collection("upscaleTransactions").doc(transactionId)

  await db.runTransaction(async (tx) => {
    const tSnap = await tx.get(txRef)
    if (!tSnap.exists) throw new Error("NOT_FOUND")
    const data = tSnap.data()!
    if (data.uid !== uid) throw new Error("FORBIDDEN")
    if (data.status !== "staged") throw new Error("INVALID_STATE")

    const userRef = db.collection("users").doc(uid)
    const uSnap = await tx.get(userRef)
    const user = uSnap.data() ?? {}

    if (data.mode === "paid") {
      const credits = Number(data.credits ?? 0)
      const staged = Number(user.stagedPaidCredits ?? 0)
      const paid = Number(user.paidCredits ?? 0)
      tx.set(userRef, {
        stagedPaidCredits: Math.max(0, staged - credits),
        paidCredits: Math.max(0, paid - credits),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, { merge: true })
    }

    tx.set(txRef, {
      status: "committed",
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true })
  }).catch((err: Error) => {
    if (["NOT_FOUND", "FORBIDDEN", "INVALID_STATE"].includes(err.message)) {
      res.status(400).json({ error: err.message })
      return
    }
    res.status(500).json({ error: "Failed to commit credits" })
  })

  if (!res.headersSent) res.json({ transactionId, status: "committed" })
})

app.post("/v1/credits/refund", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const transactionId = String(req.body?.transactionId ?? "")
  if (!transactionId) {
    res.status(400).json({ error: "transactionId is required" })
    return
  }
  const txRef = db.collection("upscaleTransactions").doc(transactionId)

  await db.runTransaction(async (tx) => {
    const tSnap = await tx.get(txRef)
    if (!tSnap.exists) throw new Error("NOT_FOUND")
    const data = tSnap.data()!
    if (data.uid !== uid) throw new Error("FORBIDDEN")
    if (data.status !== "staged") throw new Error("INVALID_STATE")

    if (data.mode === "paid") {
      const userRef = db.collection("users").doc(uid)
      const uSnap = await tx.get(userRef)
      const user = uSnap.data() ?? {}
      const credits = Number(data.credits ?? 0)
      tx.set(userRef, {
        stagedPaidCredits: Math.max(0, Number(user.stagedPaidCredits ?? 0) - credits),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, { merge: true })
    }

    tx.set(txRef, {
      status: "refunded",
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true })
  }).catch((err: Error) => {
    if (["NOT_FOUND", "FORBIDDEN", "INVALID_STATE"].includes(err.message)) {
      res.status(400).json({ error: err.message })
      return
    }
    res.status(500).json({ error: "Failed to refund" })
  })

  if (!res.headersSent) res.json({ transactionId, status: "refunded" })
})

app.post("/v1/history/add", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const item = {
    type: String(req.body?.type ?? "pdf"),
    sourceHash: String(req.body?.sourceHash ?? ""),
    localUri: String(req.body?.localUri ?? ""),
    remoteUri: String(req.body?.remoteUri ?? ""),
    metadata: req.body?.metadata ?? {},
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  }
  const ref = await db.collection("users").doc(uid).collection("history").add(item)
  res.json({ id: ref.id })
})

app.post("/v1/purchases/support-activate", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const purchaseToken = String(req.body?.purchaseToken ?? "")
  if (!purchaseToken) {
    res.status(400).json({ error: "purchaseToken is required" })
    return
  }
  // Phase 2 foundation: token verification with Play Developer API is added in phase 3.
  await db.collection("users").doc(uid).set({
    supportPurchaseActive: true,
    nagDismissed: true,
    paidCredits: admin.firestore.FieldValue.increment(3),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, { merge: true })
  res.json({ ok: true, supportPurchaseActive: true, addedCredits: 3 })
})

/**
 * RC12c — Debug-only fixture: simulate one of the four storage notifications
 * for the calling user. Writes the same shape of doc dailySweep would, then
 * fans the push out via sendPushToUser. Used by the in-app debug chips in
 * the drawer; gated by BuildConfig.DEBUG client-side.
 *
 * The synthetic values (5 credits, 12 posters, 1.2 GB, 18h) are deliberately
 * believable so the rendered notification matches what a real bill would
 * look like in the wild. A `simulated: true` flag is written on the doc so
 * we can filter it out of any future "billing history" UI.
 */
app.post("/v1/test/storage-event", async (req: AuthedRequest, res: Response) => {
  // Auth-gated only: the route ONLY pushes to the caller's own uid, so the
  // worst a malicious client could do is spam themselves with fake "Storage
  // charge" notifications. Client-side BuildConfig.DEBUG hides the chips
  // from release APKs; this route stays available everywhere as a harmless
  // dev affordance. (No env-var gate because v2 functions don't pick up
  // ALLOW_TEST_CREDITS at runtime in this project's deploy config — see
  // backend/functions/src/test-credits.ts header comments.)
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const type = String(req.body?.type ?? "")
  const allowed = ["billed", "grace_started", "deletion_imminent", "deleted"] as const
  type AllowedType = typeof allowed[number]
  if (!(allowed as readonly string[]).includes(type)) {
    res.status(400).json({ error: `type must be one of ${allowed.join("|")}` })
    return
  }

  const fakeCredits = 5
  const fakePosters = 12
  const fakeBytes = Math.round(1.2 * 1024 * 1024 * 1024)
  const fakeHours = 18
  const fakeDeleted = 3

  let title = ""
  let body = ""
  let payload: Record<string, unknown> = {}
  switch (type as AllowedType) {
    case "billed":
      title = "Storage charge"
      body = `${fakeCredits} credits charged for ${fakePosters} posters · ${formatBytes(fakeBytes)}`
      payload = { type: "storage_billed", credits: fakeCredits, bytes: fakeBytes, posters: fakePosters }
      break
    case "grace_started":
      title = "Top up credits to keep your posters"
      body = `Cloud storage paused — ${fakePosters} posters in 30-day grace period`
      payload = { type: "storage_grace_started", posters: fakePosters }
      break
    case "deletion_imminent":
      title = `Posters will be deleted in ${fakeHours}h`
      body = `Add credits to keep your ${fakePosters} stored posters`
      payload = { type: "storage_deletion_imminent", hoursUntilDelete: fakeHours, posters: fakePosters }
      break
    case "deleted":
      title = "Cloud copies removed"
      body = `${fakeDeleted} posters were deleted from cloud storage`
      payload = { type: "storage_deleted", deletedCount: fakeDeleted }
      break
  }

  const docRef = await db.collection("users").doc(uid).collection("notifications").add({
    ...payload,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    sent: false,
    simulated: true,
  })
  const delivered = await sendPushToUser(uid, title, body)
  if (delivered > 0) {
    await docRef.update({ sent: true, deliveredCount: delivered })
  }
  res.json({ delivered, title, body, notificationId: docRef.id })
})

app.get("/v1/history/list", async (req: AuthedRequest, res: Response) => {
  if (!(await requireAuth(req, res))) return
  const uid = req.uid!
  const limit = Math.min(Number(req.query.limit ?? 50), 200)
  const snap = await db.collection("users").doc(uid).collection("history")
    .orderBy("createdAt", "desc")
    .limit(limit)
    .get()
  const items = snap.docs.map((d) => ({ id: d.id, ...d.data() }))
  res.json({ items })
})

export const api = onRequest(
  {
    region: "us-central1",
    timeoutSeconds: 120,
    memory: "512MiB",
    secrets: ["FAL_KEY"],
  },
  app
)

export { getPricing, refreshPricing } from './pricing';
export { getFalBalance } from './balance';
export { dailySweep, deleteCloudCopy } from './storageBilling';
// RC22 fix: requestUpscale + getUpscaleStatus weren't being exported from
// the index, so they never deployed. AiUpscaleRepository on the client got
// a NOT_FOUND when calling functions.getHttpsCallable("requestUpscale")
// because the named function didn't exist in the project's Cloud Functions
// inventory. Adding both onCall exports here brings them online on the
// next `gcloud builds submit --config=cloudbuild-backend.yaml .`.
export { requestUpscale, getUpscaleStatus } from './upscale';
