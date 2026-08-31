/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// The client half of the caught mail screens: whether mail is being caught at all, and reading one
// caught message. Every call takes the fetch to use as its first argument rather than reaching for a
// hook, so this file stays free of React and is testable as plain functions; components pass
// `useAuthenticatedFetch()` in.
//
// Listing is deliberately absent: the messages live under an entity homepage, so the shared grid
// pages, sorts and searches them through `.paginate.json` with no client code of its own. See
// caughtMailGrid.

/** The fetch a caller supplies, normally the session-aware one from `@iap/frontend-commons/reLogin`. */
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

export const CAUGHT_MAIL_PATH = "/CaughtMail";
export const CAUGHT_MAIL_ROUTE = "/admin/mail";
export const CAUGHT_MESSAGE_TYPE = "mail/CaughtMessage";

const messagePath = (name: string): string => `${CAUGHT_MAIL_PATH}/${name}`;

export const messageRoute = (name: string): string => `${CAUGHT_MAIL_ROUTE}/${name}`;

/**
 * The message a console route names, or null when it names none — the list page itself, or an
 * address with something further down the path.
 */
export function messageNameFromRoute(route: string): string | null {
  const trimmed = route.replace(/\/+$/, "");
  if (!trimmed.startsWith(`${CAUGHT_MAIL_ROUTE}/`)) {
    return null;
  }
  const rest = trimmed.slice(CAUGHT_MAIL_ROUTE.length + 1);
  return rest.length === 0 || rest.includes("/") ? null : rest;
}

/** Whether mail is being caught rather than delivered, and how much of it has been. */
export interface CatcherStatus {
  enabled: boolean;
  total: number;
}

/** One message that would have been sent, as it was handed over. */
export interface CaughtMessage {
  path: string;
  name: string;
  subject?: string;
  caughtAt?: string;
  from: string[];
  replyTo: string[];
  to: string[];
  cc: string[];
  bcc: string[];
  /** Every header except the ones read into properties of their own, as `Name: value`. */
  headers: string[];
  textBody?: string;
  htmlBody?: string;
}

/** A JSON node as IAP's serializer emits it: properties, plus children keyed by name. */
type SerializedNode = Record<string, unknown>;

const asString = (node: SerializedNode, name: string): string | undefined => {
  const value = node[name];
  return typeof value === "string" && value.length > 0 ? value : undefined;
};

// A single-valued property is serialized as a bare string rather than a list of one, so both shapes
// have to be read: a message to one recipient is the common case, not the exception.
const asStrings = (node: SerializedNode, name: string): string[] => {
  const value = node[name];
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === "string");
  }
  return typeof value === "string" ? [ value ] : [];
};

/**
 * Whether mail is being caught, and how many messages have been.
 *
 * The two travel together because they are useless apart: a count with no idea whether catching is
 * on reads as "no mail has been sent", which is the opposite of what an empty mailbox means on an
 * instance that is delivering normally.
 *
 * @throws Error if the answer cannot be read, which on a distribution without the catcher — every
 *         production one — is what happens
 */
export async function fetchCatcherStatus(fetchUtil: AuthenticatedFetch): Promise<CatcherStatus> {
  const response = await fetchUtil(`${CAUGHT_MAIL_PATH}.status.json`);
  if (!response.ok) {
    throw new Error(`The caught mail could not be read (${String(response.status)})`);
  }
  const body = (await response.json()) as SerializedNode | null;
  if (body === null) {
    throw new Error("The caught mail could not be read");
  }
  return {
    enabled: body.enabled === true,
    total: typeof body.total === "number" ? body.total : 0,
  };
}

/**
 * One caught message, whole.
 *
 * Read at the default depth: a caught message is a leaf, everything about it is in its own
 * properties, and the bodies are the bulk of it.
 *
 * @throws Error if the message cannot be read
 */
export async function fetchCaughtMessage(
  fetchUtil: AuthenticatedFetch, name: string): Promise<CaughtMessage> {
  const response = await fetchUtil(`${messagePath(name)}.json`);
  if (!response.ok) {
    throw new Error(`The message could not be read (${String(response.status)})`);
  }
  const body = (await response.json()) as SerializedNode | null;
  if (body === null) {
    throw new Error("The message could not be read");
  }
  return {
    path: asString(body, "@path") ?? messagePath(name),
    name,
    subject: asString(body, "subject"),
    caughtAt: asString(body, "caughtAt"),
    from: asStrings(body, "from"),
    replyTo: asStrings(body, "replyTo"),
    to: asStrings(body, "to"),
    cc: asStrings(body, "cc"),
    bcc: asStrings(body, "bcc"),
    headers: asStrings(body, "headers"),
    textBody: asString(body, "textBody"),
    htmlBody: asString(body, "htmlBody"),
  };
}
