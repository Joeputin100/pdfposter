/**
 * RC48: Firestore-triggered Cloud Function that emails the dev team
 * (jc24q@pm.me) whenever a user submits a Send Feedback ticket via
 * the in-app form. Trigger fires on /support/{ticketId} create.
 *
 * Uses Resend's REST API. The free tier (3,000 emails/month) covers
 * any realistic support volume for this app. Sender starts as
 * `onboarding@resend.dev`; can be swapped to a verified custom
 * domain later by changing the `from` field.
 *
 * Secret: RESEND_API_KEY (Google Secret Manager). The secret is
 * declared on the function via `secrets:` and accessed at runtime
 * via the bound `defineSecret` handle.
 *
 * Failure mode: if Resend returns a non-2xx, the function logs and
 * does NOT throw — the user-side support flow already succeeded
 * (Firestore doc exists, user saw the toast). Retrying the email
 * later is a manual triage problem, not a user-facing one.
 */

import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { defineSecret } from "firebase-functions/params";
import { Resend } from "resend";

const RESEND_API_KEY = defineSecret("RESEND_API_KEY");

const SUPPORT_TO = "jc24q@pm.me";
// Resend's free-tier shared sender. Swap to a DNS-verified custom
// domain (e.g. notifications@<your-domain>) once one is set up.
const SUPPORT_FROM = "PosterPDF Support <onboarding@resend.dev>";

interface SupportTicket {
  uid?: string;
  email?: string;
  displayName?: string;
  isAnonymous?: boolean;
  subject?: string;
  category?: string;
  description?: string;
  diagnosticsIncluded?: boolean;
  device?: {
    manufacturer?: string;
    model?: string;
    androidSdk?: number;
    androidRelease?: string;
    appVersion?: string;
    appVersionCode?: number;
  };
  debugLogTail?: string;
}

function buildSubject(t: SupportTicket): string {
  const cat = t.category ? `[${t.category}] ` : "";
  const subj = (t.subject || "(no subject)").trim();
  return `${cat}${subj}`;
}

function buildBody(ticketId: string, t: SupportTicket): string {
  const lines: string[] = [];
  lines.push(`Ticket ID: ${ticketId}`);
  lines.push(`From: ${t.displayName || "(no name)"} <${t.email || "(no email)"}>`);
  lines.push(`uid: ${t.uid || "(none)"}`);
  if (t.isAnonymous) lines.push(`(anonymous Firebase account)`);
  lines.push(`Category: ${t.category || "(none)"}`);
  lines.push("");
  lines.push("--- Description ---");
  lines.push(t.description || "(empty)");
  lines.push("");
  if (t.diagnosticsIncluded && t.device) {
    lines.push("--- Diagnostics (user opted in) ---");
    lines.push(
      `${t.device.manufacturer ?? "?"} ${t.device.model ?? "?"} | ` +
      `Android ${t.device.androidRelease ?? "?"} (SDK ${t.device.androidSdk ?? "?"}) | ` +
      `app ${t.device.appVersion ?? "?"} (${t.device.appVersionCode ?? "?"})`,
    );
    if (t.debugLogTail) {
      lines.push("");
      lines.push("--- Debug log tail (last ~16 KB) ---");
      lines.push(t.debugLogTail);
    }
  }
  return lines.join("\n");
}

export const onSupportCreate = onDocumentCreated(
  {
    document: "support/{ticketId}",
    region: "us-central1",
    secrets: [RESEND_API_KEY],
  },
  async (event) => {
    const snap = event.data;
    if (!snap) {
      console.warn("onSupportCreate: no snapshot in event");
      return;
    }
    const ticketId = event.params.ticketId;
    const ticket = snap.data() as SupportTicket;
    const subject = buildSubject(ticket);
    const text = buildBody(ticketId, ticket);

    const resend = new Resend(RESEND_API_KEY.value());
    try {
      const result = await resend.emails.send({
        from: SUPPORT_FROM,
        to: SUPPORT_TO,
        // Set replyTo to the submitting user's email when present so a
        // direct reply from the dev's inbox lands back with the user.
        ...(ticket.email ? { replyTo: ticket.email } : {}),
        subject,
        text,
      });
      if (result.error) {
        console.warn(`Resend rejected ticket ${ticketId}:`, result.error);
      } else {
        console.log(`Resend accepted ticket ${ticketId} (msgId=${result.data?.id})`);
      }
    } catch (e) {
      console.error(`Resend threw for ticket ${ticketId}:`, e);
    }
  },
);
