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

// What a caught message is, where it lives, and how to read one out of the serializer's JSON. No
// React, no fetch: everything here is a pure function of its arguments, so it can be tested without
// rendering anything or stubbing a response. The I/O that uses it is in useCaughtMail.
//
// Listing is deliberately absent: the messages live under an entity homepage, so the shared grid
// pages, sorts and searches them through `.paginate.json` with no client code of its own. See
// caughtMailGrid.

export const CAUGHT_MAIL_PATH = "/CaughtMail";
export const CAUGHT_MAIL_ROUTE = "/admin/mail";
export const CAUGHT_MESSAGE_TYPE = "mail/CaughtMessage";

/** Where the catcher reports whether it is on, and how much it holds. */
export const CATCHER_STATUS_PATH = `${CAUGHT_MAIL_PATH}.status.json`;

/** Where one message is stored. */
export const messagePath = (name: string): string => `${CAUGHT_MAIL_PATH}/${name}`;

/** Where one message is read in the console. */
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
export type SerializedNode = Record<string, unknown>;

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
 * An answer missing either half is read as the safe reading rather than refused — claiming mail is
 * being caught when the answer did not say so would send somebody looking for a message that was in
 * fact delivered.
 */
export const parseCatcherStatus = (node: SerializedNode): CatcherStatus => ({
  enabled: node.enabled === true,
  total: typeof node.total === "number" ? node.total : 0,
});

/**
 * One caught message, whole.
 *
 * Absent properties are left absent rather than filled with empty ones: a message with no subject
 * and no HTML body is a message rather than a broken one, and the viewer shows different things for
 * "empty" and "not there".
 */
export const parseCaughtMessage = (node: SerializedNode, name: string): CaughtMessage => ({
  path: asString(node, "@path") ?? messagePath(name),
  name,
  subject: asString(node, "subject"),
  caughtAt: asString(node, "caughtAt"),
  from: asStrings(node, "from"),
  replyTo: asStrings(node, "replyTo"),
  to: asStrings(node, "to"),
  cc: asStrings(node, "cc"),
  bcc: asStrings(node, "bcc"),
  headers: asStrings(node, "headers"),
  textBody: asString(node, "textBody"),
  htmlBody: asString(node, "htmlBody"),
});
