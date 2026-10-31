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

import { escapeJQL } from "../escape";
import { type AuthenticatedFetch } from "../reLogin";
import { RequestError } from "../requestFailure";
import { type SerializedNode } from "../serializedNode";
import { type ChoiceSource } from "./choiceSource";

/** The nodes a reference may point at. With no type given it may point anywhere. */
export interface ReferenceTarget {
  /** The `sling:resourceType` they have, e.g. `"wf/WorkflowVersion"` */
  resourceType?: string;
  /** The JCR node type they are, a subtype of it, or a node with it as a mixin, e.g. `"sch:Schema"` */
  nodeType?: string;
  /** The node they are under, e.g. `"/Workflows"` */
  root?: string;
}

/** A node a reference may point at. */
export interface ReferenceCandidate {
  /** The node's absolute path */
  path: string;
  label: string;
  /** The node's `jcr:uuid` which a reference may store instead of the path */
  identifier?: string;
}

// A JCR name never holds a bracket, and one in a node type would end the selector's name early
const BRACKET = /[[\]]/;

/** The query for the nodes a reference may point at or undefined if not specified. */
export function referenceQuery({ resourceType, nodeType = "", root }: ReferenceTarget): string | undefined {
  if (!resourceType && !nodeType || BRACKET.test(nodeType)) {
    return undefined;
  }
  const under = root?.replace(/(.)\/+$/, "$1");
  const conditions = [
    ...resourceType ? [ `n.[sling:resourceType] = '${escapeJQL(resourceType)}'` ] : [],
    ...under ? [ `isdescendantnode(n, '${escapeJQL(under)}')` ] : [],
  ];
  return `select * from [${nodeType === "" ? "nt:base" : nodeType}] as n`
    + (conditions.length > 0 ? ` where ${conditions.join(" and ")}` : "");
}

/** A node as a reference offers it: by its title or label, else by its path under the root. */
export function candidateOf(node: SerializedNode, root?: string): ReferenceCandidate | undefined {
  const path = node["@path"];
  if (typeof path !== "string") {
    return undefined;
  }
  const named = [ node.title, node.label ].find(name => typeof name === "string" && name.trim() !== "");
  const base = root?.replace(/\/*$/, "/");
  const relative = base && path.startsWith(base) ? path.substring(base.length) : path;
  const identifier = node["jcr:uuid"];
  return { path, label: typeof named === "string" ? named : relative,
    ...typeof identifier === "string" ? { identifier } : {} };
}

/** The nodes a reference may point at, at most a thousand, sorted by their labels. */
export async function readReferences(doFetch: AuthenticatedFetch, target: ReferenceTarget)
  : Promise<ReferenceCandidate[]> {
  const query = referenceQuery(target);
  if (!query) {
    return [];
  }
  const response = await doFetch(`/search.json?${new URLSearchParams({ query, limit: "1000" }).toString()}`);
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  const { rows } = await response.json() as { rows?: SerializedNode[] };
  return (rows ?? [])
    .map(row => candidateOf(row, target.root))
    .filter((candidate): candidate is ReferenceCandidate => candidate !== undefined)
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** The nodes a reference may point at, as a source of choices, each stored as its path. */
export const referenceChoices = (target: ReferenceTarget): ChoiceSource => ({
  key: `reference:${JSON.stringify(target)}`,
  search: async doFetch =>
    (await readReferences(doFetch, target)).map(({ path, label }) => ({ value: path, label })),
  labelOf: path => path,
});
