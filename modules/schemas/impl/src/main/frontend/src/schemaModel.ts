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

// Reading schemas and versions straight from the repository's JSON serialization, which is the one
// description of their shape. Where each stands is its tags; what may be done with it is the events
// the server offers on it; what may be edited is the fields it describes. No React, no fetch.

export type JcrNode = Record<string, unknown>;

export const SCHEMAS_ROOT = "/Schemas";

const isNode = (value: unknown, primaryType: string): value is JcrNode =>
  typeof value === "object" && value !== null && (value as JcrNode)["jcr:primaryType"] === primaryType;

const strings = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];

const text = (node: JcrNode, key: string): string | undefined => {
  const value = node[key];
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
};

export const pathOf = (node: JcrNode): string => String(node["@path"]);

export const tagsOf = (node: JcrNode): string[] => strings(node.tags);

export const nameOf = (node: JcrNode): string => String(node["@name"]);

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

// The label a new version would most likely have: the next whole number after the highest numeric label
export function nextVersionLabel(schema: JcrNode): string {
  const versions = versionsOf(schema);
  const numbers = versions.map(version => Number.parseFloat(labelOf(version))).filter(Number.isFinite);
  return `${numbers.length > 0 ? Math.floor(Math.max(...numbers)) + 1 : versions.length + 1}.0`;
}

export const schemasOf = (homepage: JcrNode): JcrNode[] =>
  Object.values(homepage).filter(value => isNode(value, "sch:Schema"));

// What the update that would run says it allows, in words
export const noticeOf = (node: JcrNode): string | undefined => text(node, "@notice");

// Whether the server would take this event on a schema or a version from the current user
export const offers = (node: JcrNode, event: string): boolean => strings(node["@events"]).includes(event);

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

// The name of a schema from the page it is shown on, e.g. /admin/schemas/clinicalStudy.
export function schemaNameFromRoute(pathname: string): string {
  const trimmed = pathname.replace(/\/+$/, "");
  return decodeURIComponent(trimmed.slice(trimmed.lastIndexOf("/") + 1));
}
