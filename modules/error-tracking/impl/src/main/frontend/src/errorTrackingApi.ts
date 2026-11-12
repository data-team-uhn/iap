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

import { readJson } from "@iap/frontend-commons/requestFailure";
import { childrenOfType, type SerializedNode, stringsOf } from "@iap/frontend-commons/serializedNode";

// The client side of the error triage pages: reading one recorded error with the decisions taken
// about it, and recording a new decision. Every call takes the fetch to use as its first argument,
// so this file needs no React; components pass `useAuthenticatedFetch()`.


/** The fetch a caller supplies, normally the session-aware one from `@iap/frontend-commons/reLogin`. */
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

/** The homepage of the recorded errors. Each error is a child named after its fingerprint. */
export const LOGGED_ERRORS_PATH = "/LoggedErrors";

/** The administration console page where errors are triaged, which differs from their repository path. */
export const ERRORS_ROUTE = "/admin/errors";

/** The resource type of a recorded error, which the grid registry and the pagination servlet use. */
export const LOGGED_ERROR_TYPE = "err/LoggedError";

/** The tag category of the triage tags. */
export const TRIAGE_CATEGORY = "error-triage";

/**
 * The tag of an error that needs attention: nobody has acknowledged it, or it happened again since.
 * It is computed from the decisions on the error, so nobody can choose it, and it is not among
 * RESOLUTIONS.
 */
export const UNACKNOWLEDGED = "unacknowledged";

/** The property that holds the tags computed from an error's own content, the triage tags among them. */
export const TRIAGE_PROPERTY = "computedTags";

/** One decision a person may record about an error. */
export interface Resolution {
  /** The tag name sent as the `resolution` parameter. */
  name: string;
  label: string;
  /** What choosing it means, shown beside the choice. */
  hint: string;
}

/**
 * The decisions the page offers, in order: the three triage tags this module ships, other than
 * `unacknowledged`. A tag a deployment adds to the category is not offered.
 */
export const RESOLUTIONS: Resolution[] = [
  { name: "acknowledged", label: "Acknowledged", hint: "Seen and being dealt with" },
  { name: "known-issue", label: "Known issue", hint: "Understood, and a fix is planned" },
  { name: "wont-fix", label: "Won't fix", hint: "Deliberately not being dealt with" },
];

/**
 * The simple name of a fully qualified Java class name, such as `IllegalStateException` for
 * `java.lang.IllegalStateException`, or an empty string for a value that is not a string.
 *
 * It takes `unknown` because a grid column's `valueGetter` receives a value typed from the row, an
 * EntityRow, which is a `Record<string, unknown>`.
 */
export function simpleName(value: unknown): string {
  return typeof value === "string" ? value.slice(value.lastIndexOf(".") + 1) : "";
}

/** The label of a triage tag, or the tag's name for one this module does not ship. */
export function resolutionLabel(name: string): string {
  if (name === UNACKNOWLEDGED) {
    return "Needs attention";
  }
  return RESOLUTIONS.find(resolution => resolution.name === name)?.label ?? name;
}

/** One decision somebody took about one error. */
export interface Decision {
  name: string;
  resolution: string;
  note?: string;
  /** What the error's occurrence count had reached when this was decided. */
  acknowledgedOccurrences?: number;
  created?: string;
  createdBy?: string;
}

/** One recorded error, with everything the triage page shows about it. */
export interface LoggedErrorDetail {
  path: string;
  name: string;
  /** A thrown failure carries a stack trace; a problem carries a phrase instead. */
  kind: "failure" | "problem";
  component?: string;
  operation?: string;
  occurrences: number;
  /** When the fault was first seen; `jcr:created` on the node. */
  firstSeen?: string;
  lastOccurrence?: string;
  messages: string[];
  subjects: string[];
  actors: string[];
  lastContext?: string;
  /** The throwable's class, for a failure. */
  type?: string;
  stackTrace?: string;
  /** What is wrong, for a problem. */
  problem?: string;
  /** The tags computed from the error's own content, the triage tags among them. */
  triage: string[];
  /** The decisions taken, newest first. */
  decisions: Decision[];
}

/** What recording a decision answered with. */
export type AcknowledgeStatus =
  | "ok"        // the decision was recorded
  | "invalid"   // the resolution did not name a triage tag
  | "missing"   // there is no recorded error there
  | "failed";   // the server could not record it

export interface AcknowledgeOutcome {
  status: AcknowledgeStatus;
  /** A sentence to show the user, for any status but `ok`. */
  message?: string;
}

/** The console route showing one error, addressed by its fingerprint. */
export const errorRoute = (name: string): string => `${ERRORS_ROUTE}/${name}`;

/**
 * The fingerprint in a console route, or null if the route names no single error, as for the list
 * page itself or a path deeper than one error.
 */
export function errorNameFromRoute(route: string): string | null {
  const trimmed = route.replace(/\/+$/, "");
  if (!trimmed.startsWith(`${ERRORS_ROUTE}/`)) {
    return null;
  }
  const rest = trimmed.slice(ERRORS_ROUTE.length + 1);
  return rest.length === 0 || rest.includes("/") ? null : rest;
}

/** The repository path of one error. */
const errorPath = (name: string): string => `${LOGGED_ERRORS_PATH}/${name}`;

/** Reads a property as a string, ignoring anything that is not one. */
const asString = (node: SerializedNode, name: string): string | undefined => {
  const value = node[name];
  return typeof value === "string" && value.length > 0 ? value : undefined;
};

const asNumber = (node: SerializedNode, name: string): number | undefined => {
  const value = node[name];
  return typeof value === "number" ? value : undefined;
};

/** Newest decision first, by how much had happened when it was taken and then by when. */
const NEWEST_FIRST = (left: Decision, right: Decision): number => {
  const occurrences = (right.acknowledgedOccurrences ?? 0) - (left.acknowledgedOccurrences ?? 0);
  return occurrences === 0 ? (right.created ?? "").localeCompare(left.created ?? "") : occurrences;
};

function readDecision(node: SerializedNode): Decision {
  return {
    name: asString(node, "@name") ?? "",
    resolution: asString(node, "resolution") ?? "",
    note: asString(node, "note"),
    acknowledgedOccurrences: asNumber(node, "acknowledgedOccurrences"),
    created: asString(node, "jcr:created"),
    createdBy: asString(node, "jcr:createdBy"),
  };
}

function readError(name: string, node: SerializedNode): LoggedErrorDetail {
  const problem = asString(node, "problem");
  return {
    path: asString(node, "@path") ?? errorPath(name),
    name: asString(node, "@name") ?? name,
    kind: problem === undefined ? "failure" : "problem",
    component: asString(node, "component"),
    operation: asString(node, "operation"),
    occurrences: asNumber(node, "occurrences") ?? 0,
    firstSeen: asString(node, "jcr:created"),
    lastOccurrence: asString(node, "lastOccurrence"),
    messages: stringsOf(node.messages),
    subjects: stringsOf(node.subjects),
    actors: stringsOf(node.actors),
    lastContext: asString(node, "lastContext"),
    type: asString(node, "type"),
    stackTrace: asString(node, "stackTrace"),
    problem,
    triage: stringsOf(node[TRIAGE_PROPERTY]),
    decisions: childrenOfType(node, "err/Acknowledgement").map(readDecision).sort(NEWEST_FIRST),
  };
}

/**
 * Reads one recorded error and the decisions taken about it, which are its children. Depth 1 reads
 * nothing below them.
 */
export async function fetchLoggedError(
  fetchUtil: AuthenticatedFetch, name: string): Promise<LoggedErrorDetail> {
  const response = await fetchUtil(`${errorPath(name)}.1.json`);
  if (!response.ok) {
    throw new Error(`The recorded error could not be read (${String(response.status)})`);
  }
  const body = await readJson<SerializedNode | null>(response);
  if (body === null) {
    throw new Error("The recorded error could not be read");
  }
  return readError(name, body);
}

/**
 * Records a decision about one error.
 *
 * The URL needs the `.json` extension. Without it, Sling reads `acknowledge` as the extension, and
 * its default POST servlet writes a `resolution` property onto the error instead.
 */
export async function acknowledgeError(fetchUtil: AuthenticatedFetch, name: string,
  resolution: string, note?: string): Promise<AcknowledgeOutcome> {
  const body = new URLSearchParams({ resolution });
  if (note !== undefined && note.trim().length > 0) {
    body.set("note", note.trim());
  }
  const response = await fetchUtil(`${errorPath(name)}.acknowledge.json`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  // The servlet answers a refusal as {"status":"error","error":"<sentence>"}, and its sentence is
  // shown if there is one. Only the status decides success.
  const answer = (await response.json().catch(() => null)) as SerializedNode | null;
  const message = answer === null ? undefined : asString(answer, "error");
  if (response.ok) {
    return { status: "ok" };
  }
  if (response.status === 400) {
    return { status: "invalid", message: message ?? "That is not a decision this error can carry." };
  }
  if (response.status === 404) {
    return { status: "missing", message: message ?? "There is no recorded error here any more." };
  }
  return { status: "failed", message: message ?? "The decision could not be recorded." };
}
