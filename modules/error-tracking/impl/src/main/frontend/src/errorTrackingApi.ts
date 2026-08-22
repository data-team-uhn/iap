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

// The client half of the error triage screens: counting what needs attention, reading one recorded
// error with the decisions taken about it, and recording a new decision. Every call takes the fetch
// to use as its first argument rather than reaching for a hook, so this file stays free of React and
// is testable as plain functions; components pass `useAuthenticatedFetch()` in.

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";

/** The fetch a caller supplies, normally the session-aware one from `@iap/frontend-commons/reLogin`. */
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

/** Where the recorded errors live. Flat: an error is named after its fingerprint. */
export const LOGGED_ERRORS_PATH = "/LoggedErrors";

/**
 * Where the errors are triaged, which is a page of the administration console rather than the
 * repository path they live at. The two are deliberately kept next to each other: a route that
 * drifted from the resource path would navigate somewhere real and fetch from somewhere that is not.
 */
export const ERRORS_ROUTE = "/admin/errors";

/** The resource type the grid registry and the pagination servlet know these by. */
export const LOGGED_ERROR_TYPE = "err/LoggedError";

/** The tag category the triage markers live in. Mirrors LoggedError.TRIAGE_CATEGORY. */
export const TRIAGE_CATEGORY = "error-triage";

/**
 * The marker an error carries while it still needs attention — either nobody has looked at it, or it
 * has happened again since somebody decided it was dealt with. Derived from the decisions on the
 * error, so it is never chosen by a person, which is why it is not among RESOLUTIONS below.
 */
export const UNACKNOWLEDGED = "unacknowledged";

/** The property the triage markers are derived into: the tags module's LOCAL phase writes here. */
export const TRIAGE_PROPERTY = "computedTags";

/** One decision a person may record about an error. */
export interface Resolution {
  /** The tag name sent as the `resolution` parameter. */
  name: string;
  label: string;
  /** What choosing it means, shown beside the choice so the three are told apart. */
  hint: string;
}

/**
 * The decisions offered, in the order they are offered. Deliberately not read from `/Tags`: these
 * are the three a person may *take*, whereas the category also holds `unacknowledged`, which is
 * derived and would be a nonsensical thing to choose. Their labels match the shipped definitions.
 */
export const RESOLUTIONS: Resolution[] = [
  { name: "acknowledged", label: "Acknowledged", hint: "Seen and being dealt with" },
  { name: "known-issue", label: "Known issue", hint: "Understood, and left as it is for now" },
  { name: "wont-fix", label: "Won't fix", hint: "Deliberately not being dealt with" },
];

/**
 * The unqualified tail of a dotted Java name, for the places that show one: the package repeats down
 * a whole column, and adds a line's worth of noise to a heading, while the simple name is what tells
 * one fault from another. The fully-qualified name is shown once, as a fact on the error's own page.
 *
 * Takes `unknown` rather than `string` on purpose. A grid column's `valueGetter` is handed a value
 * the grid types from the row, and an EntityRow is a `Record<string, unknown>`, so inspecting the
 * parameter inline narrows it to `never` and fails the typecheck; every other grid in the repo passes
 * it to a helper for exactly this reason.
 */
export function simpleName(value: unknown): string {
  return typeof value === "string" ? value.slice(value.lastIndexOf(".") + 1) : "";
}

/** The label to show for a triage marker, falling back to the raw name for one we do not know. */
export function resolutionLabel(name: string): string {
  if (name === UNACKNOWLEDGED) {
    return "Needs attention";
  }
  return RESOLUTIONS.find(resolution => resolution.name === name)?.label ?? name;
}

/** How many recorded errors there are, and how many of them still need attention. */
export interface TriageCounts {
  needingAttention: number;
  total: number;
  /** Whether either count stopped at the server's bound, so both are lower bounds. */
  approximate: boolean;
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

/** One recorded error, with everything the triage screen shows about it. */
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
  /** The triage markers currently derived onto the error. */
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
  /** A sentence fit to show the user, whenever there is something to explain. */
  message?: string;
}

/** The console route showing one error, addressed by its fingerprint. */
export const errorRoute = (name: string): string => `${ERRORS_ROUTE}/${name}`;

/**
 * The fingerprint named by a console route, or null when the route names no single error — the
 * browse page itself, or anything deeper than one error. Reported rather than fetched, so a route
 * that cannot identify an error says so instead of requesting nonsense.
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

/**
 * Counts the recorded errors, and how many of them still need attention.
 *
 * Both come from the pagination servlet the errors' homepage already answers, asked for a single row
 * each: the count wanted is `totalrows`, and asking for no rows at all would still pay for the query
 * without proving it works. There is deliberately no dedicated summary endpoint — the homepage is an
 * iap:EntityHomepage, so this needs no server-side code at all.
 */
export async function fetchTriageCounts(fetchUtil: AuthenticatedFetch): Promise<TriageCounts> {
  const [ needing, all ] = await Promise.all([
    fetchEntityPage(fetchUtil, {
      homepage: LOGGED_ERRORS_PATH,
      limit: 1,
      filters: [ { name: TRIAGE_PROPERTY, value: UNACKNOWLEDGED } ],
    }),
    fetchEntityPage(fetchUtil, { homepage: LOGGED_ERRORS_PATH, limit: 1 }),
  ]);
  return {
    needingAttention: needing.totalrows,
    total: all.totalrows,
    approximate: needing.totalIsApproximate || all.totalIsApproximate,
  };
}

/** A JSON node as IAP's serializer emits it: properties, plus children keyed by name. */
type SerializedNode = Record<string, unknown>;

/** Reads a property as a string, ignoring anything that is not one. */
const asString = (node: SerializedNode, name: string): string | undefined => {
  const value = node[name];
  return typeof value === "string" && value.length > 0 ? value : undefined;
};

/**
 * Reads a multi-valued property as an array of strings. A single-valued property round-trips as a
 * bare string rather than a one-element array, so both shapes are accepted.
 */
const asStrings = (node: SerializedNode, name: string): string[] => {
  const value = node[name];
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === "string");
  }
  return typeof value === "string" ? [ value ] : [];
};

const asNumber = (node: SerializedNode, name: string): number | undefined => {
  const value = node[name];
  return typeof value === "number" ? value : undefined;
};

/** Whether a serialized value is a child node rather than a property. */
const isNode = (value: unknown): value is SerializedNode =>
  typeof value === "object" && value !== null && !Array.isArray(value);

/** The children of a serialized node that carry a given resource type. */
const childrenOfType = (node: SerializedNode, resourceType: string): SerializedNode[] =>
  Object.entries(node)
    .filter(([ key ]) => !key.startsWith("@"))
    .map(([ , value ]) => value)
    .filter(isNode)
    .filter(child => child["sling:resourceType"] === resourceType);

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
    messages: asStrings(node, "messages"),
    subjects: asStrings(node, "subjects"),
    actors: asStrings(node, "actors"),
    lastContext: asString(node, "lastContext"),
    type: asString(node, "type"),
    stackTrace: asString(node, "stackTrace"),
    problem,
    // Only the triage markers, so an unrelated tag on the error is not shown as a decision
    triage: asStrings(node, TRIAGE_PROPERTY),
    decisions: childrenOfType(node, "err/Acknowledgement").map(readDecision).sort(NEWEST_FIRST),
  };
}

/**
 * Reads one recorded error and the decisions taken about it.
 *
 * Depth 1 rather than `deep`: the decisions are direct children and nothing below them is shown, and
 * a bounded depth stays correct as the nodes grow children.
 */
export async function fetchLoggedError(
  fetchUtil: AuthenticatedFetch, name: string): Promise<LoggedErrorDetail> {
  const response = await fetchUtil(`${errorPath(name)}.1.json`);
  if (!response.ok) {
    throw new Error(`The recorded error could not be read (${String(response.status)})`);
  }
  const body = (await response.json()) as SerializedNode | null;
  if (body === null) {
    throw new Error("The recorded error could not be read");
  }
  return readError(name, body);
}

/**
 * Records a decision about one error.
 *
 * The URL carries the selector *and* an extension. `.acknowledge` on its own would not reach the
 * servlet at all: Sling reads the last dot-separated token as the extension, so there would be no
 * selector to match, and the request would fall through to the default POST servlet — which would
 * cheerfully write a `resolution` property onto the error instead of recording a decision.
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
  // The servlet answers a refusal as {"status":"error","error":"<sentence>"}; prefer its own words,
  // which name what was wrong, over anything this side could guess. An unreadable body is not taken
  // as success: only the status decides that.
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
