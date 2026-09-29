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

import { nextVersionLabel as labelAfter } from "@iap/frontend-commons/versionNumbers";

// Reading schemas and versions straight from the repository's JSON serialization, which is the one
// description of their shape. Where each stands is its tags; what may be done with it is the events
// the server offers on it; what may be edited is the fields it describes. No React, no fetch.

export type JcrNode = Record<string, unknown>;

export const SCHEMAS_ROOT = "/Schemas";

// How listings read schemas and versions. `simple` drops the repository's bookkeeping, and with a
// version its content too. `events` and `fields` add what the current user may do with each node, and
// which of its fields an update would change. `-active` keeps the drafts and the retired, which the
// schema serialization leaves out of a listing by default.
export const listing = (depth: number): string => `${depth}.simple.events.fields.-active`;

const isNode = (value: unknown, primaryType: string): value is JcrNode =>
  typeof value === "object" && value !== null && (value as JcrNode)["jcr:primaryType"] === primaryType;

const strings = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];

const text = (node: JcrNode, key: string): string | undefined => {
  const value = node[key];
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
};

export const isObject = (value: unknown): value is JcrNode => typeof value === "object" && value !== null
  && !Array.isArray(value);

export const pathOf = (node: JcrNode): string => String(node["@path"]);

// The last step of a path: the name of what it leads to
export const lastSegmentOf = (path: string): string => path.slice(path.lastIndexOf("/") + 1);

// Where a node would be, renamed where it stands
export const renamedPath = (path: string, name: string): string => `${path.slice(0, path.lastIndexOf("/"))}/${name}`;

export const tagsOf = (node: JcrNode): string[] => strings(node.tags);

export const nameOf = (node: JcrNode): string => String(node["@name"]);

// What a node holds, as its keys come
export const childrenOf = (node: JcrNode): JcrNode[] => Object.values(node).filter(isObject);

// The names of what a node holds
export const childNamesOf = (node: JcrNode): string[] => childrenOf(node).map(nameOf);

// The name of a node that may not be there, such as the one after the last
export const nameIfAny = (node?: JcrNode): string | undefined => node && nameOf(node);

export const titleOf = (schema: JcrNode): string => text(schema, "title") ?? nameOf(schema);

export const labelOf = (version: JcrNode): string => text(version, "version") ?? nameOf(version);

export const descriptionOf = (version: JcrNode): string | undefined => text(version, "description");

// In label order, numbers compared as numbers: a schema does not order its versions itself
export const versionsOf = (schema: JcrNode): JcrNode[] => Object.values(schema)
  .filter(value => isNode(value, "sch:SchemaVersion"))
  .sort((one, other) => labelOf(one).localeCompare(labelOf(other), undefined, { numeric: true }));

const createdOf = (version: JcrNode): number => Date.parse(text(version, "jcr:created") ?? "") || 0;

// The version made most recently, which a new version is most likely a revision of
export const latestVersion = (schema: JcrNode): JcrNode | undefined => versionsOf(schema)
  .reduce<JcrNode | undefined>((latest, version) =>
    latest && createdOf(latest) > createdOf(version) ? latest : version, undefined);

// The label the server gives a new version when none is asked for
export const nextVersionLabel = (schema: JcrNode): string =>
  labelAfter(versionsOf(schema).map(version => String(version["@name"])));

export const schemasOf = (homepage: JcrNode): JcrNode[] =>
  Object.values(homepage).filter(value => isNode(value, "sch:Schema"));

// What the update that would run says it allows, in words
export const noticeOf = (node: JcrNode): string | undefined => text(node, "@notice");

export interface SchemaCounts {
  active: number;
  drafts: number;
  retired: number;
}

// What the dashboard widget reports: how many versions and schemas carry each lifecycle tag.
export function countSchemas(schemas: JcrNode[]): SchemaCounts {
  const versions = schemas.flatMap(versionsOf);
  return {
    active: versions.filter(version => tagsOf(version).includes("active")).length,
    drafts: versions.filter(version => tagsOf(version).includes("draft")).length,
    retired: schemas.filter(schema => tagsOf(schema).includes("retired")).length,
  };
}

// The names a path holds: a schema's, then its version's if there is one. A page's path and the
// repository's have the same shape, /admin/schemas/clinicalStudy/v2 and /Schemas/clinicalStudy/v2.
function namesIn(path: string): string[] {
  const segments = path.split("/").filter(Boolean).map(decodeURIComponent);
  return segments.slice(segments[0] === "admin" ? 2 : 1);
}

// The name of the schema a path leads to, e.g. clinicalStudy.
export function schemaNameFromRoute(path: string): string {
  return namesIn(path)[0] ?? "";
}

// The name of the version a path leads to, if it leads to one, e.g. v2.
export function versionNameFromRoute(path: string): string | undefined {
  return namesIn(path)[1];
}
