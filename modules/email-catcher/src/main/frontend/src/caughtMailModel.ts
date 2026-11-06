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

import { type SerializedNode, stringsOf } from "@iap/frontend-commons/serializedNode";

// The paths and routes of the caught mail, and how to read a caught message and the catcher's
// status out of the server's JSON. The requests are in useCaughtMail.

export const CAUGHT_MAIL_PATH = "/CaughtMail";
export const CAUGHT_MAIL_ROUTE = "/admin/mail";
export const CAUGHT_MESSAGE_TYPE = "mail/CaughtMessage";

/** Where the catcher reports whether it is on, and how many messages it holds. */
export const CATCHER_STATUS_PATH = `${CAUGHT_MAIL_PATH}.adminSummary.json`;

/** Where anyone, signed in or not, may ask whether mail is being caught. */
export const CATCHING_PATH = "/libs/iap/mail-catcher.catching.json";

/**
 * The event fired on `window` when catching has just been switched on or off. Other tabs see the
 * change on their next page load.
 */
export const CATCHING_CHANGED_EVENT = "iap:mail-catching-changed";

/** Where an administrator switches catching on and off. */
export const CATCHER_TOGGLE_PATH = `${CAUGHT_MAIL_PATH}.catching.json`;

/** The path of one message, from its node name. */
export const messagePath = (name: string): string => `${CAUGHT_MAIL_PATH}/${name}`;

/** The console route of one message, from its node name. */
export const messageRoute = (name: string): string => `${CAUGHT_MAIL_ROUTE}/${name}`;

/**
 * The node name of the message a console route names. Null for the list page itself, and for a
 * route with more segments after the name.
 */
export function messageNameFromRoute(route: string): string | null {
  const trimmed = route.replace(/\/+$/, "");
  if (!trimmed.startsWith(`${CAUGHT_MAIL_ROUTE}/`)) {
    return null;
  }
  const rest = trimmed.slice(CAUGHT_MAIL_ROUTE.length + 1);
  return rest.length === 0 || rest.includes("/") ? null : rest;
}

/** Whether mail is being caught rather than delivered, and how many messages have been. */
export interface CatcherStatus {
  enabled: boolean;
  total: number;
}

/** One message that would have been sent. A header or body the message lacked is undefined. */
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
  /** Every other header, as `Name: value`. */
  headers: string[];
  textBody?: string;
  htmlBody?: string;
}

const asString = (node: SerializedNode, name: string): string | undefined => {
  const value = node[name];
  return typeof value === "string" && value.length > 0 ? value : undefined;
};

/**
 * The catcher's status, from its admin summary. An answer that does not say catching is on reads
 * as off, and a missing count reads as 0.
 */
export const parseCatcherStatus = (node: SerializedNode): CatcherStatus => ({
  enabled: figureValue(node, "enabled") === true,
  total: typeof figureValue(node, "total") === "number" ? figureValue(node, "total") as number : 0,
});

const figureValue = (node: SerializedNode, name: string): unknown => {
  const figure = node[name];
  return typeof figure === "object" && figure !== null ? (figure as SerializedNode).value : undefined;
};

/**
 * One caught message, from its node's JSON. An absent or empty subject, date or body stays
 * undefined, and an absent address or header list is empty.
 */
export const parseCaughtMessage = (node: SerializedNode, name: string): CaughtMessage => ({
  path: asString(node, "@path") ?? messagePath(name),
  name,
  subject: asString(node, "subject"),
  caughtAt: asString(node, "caughtAt"),
  from: stringsOf(node.from),
  replyTo: stringsOf(node.replyTo),
  to: stringsOf(node.to),
  cc: stringsOf(node.cc),
  bcc: stringsOf(node.bcc),
  headers: stringsOf(node.headers),
  textBody: asString(node, "textBody"),
  htmlBody: asString(node, "htmlBody"),
});
