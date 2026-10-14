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

import { childrenOf, isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

import { createdOf, nameOf, pathOf, tagsOf } from "./schemaModel";

// Which version of a schema another is compared with when nobody has named one. No React, no fetch.

/**
 * Whether a link's reference points at the version. The reference may be a path, an identifier, or the node it was
 * dereferenced to, depending on how the link was serialized.
 */
const pointsAt = (value: unknown, version: SerializedNode): boolean => (isNode(value)
  ? pathOf(value) === pathOf(version)
  : value === pathOf(version) || value === version["jcr:uuid"]);

/**
 * The version a version was copied from, as its `copiedFrom` link names it.
 *
 * @param links the version's `link:links` node, serialized one level deep; undefined while it is being read
 * @param versions the schema's versions, among which the source is looked for
 * @returns the version it was copied from, or undefined when it has no `copiedFrom` link, or the version that link
 *     names is not among `versions`
 */
export function sourceOf(links: SerializedNode | undefined, versions: SerializedNode[]): SerializedNode | undefined {
  const link = childrenOf(links ?? {}).find(child => {
    const type = isNode(child.type) ? pathOf(child.type) : String(child.type);
    return type.endsWith("/copiedFrom");
  });
  return link && versions.find(version => pointsAt(link.reference, version));
}

/**
 * The version another is compared with when nobody has named one. The rules are tried in their order, and the first
 * that finds a version other than `version` decides.
 *
 * @param version the version being compared
 * @param versions the schema's versions, `version` included
 * @param rules the names of the rules to try, as the schema view's `comparisonDefaults` gives them: `active` finds
 *     the active version, `source` the version it was copied from, and `previous` the version created last before
 *     it. A name this does not know finds nothing.
 * @param source the version it was copied from, as `sourceOf` finds it; without it, `source` finds nothing
 * @returns the version to compare it with, or undefined when no rule finds one
 */
export function defaultBase(version: SerializedNode, versions: SerializedNode[], rules: string[],
  source?: SerializedNode): SerializedNode | undefined {
  const others = versions.filter(other => nameOf(other) !== nameOf(version));
  const found: Record<string, () => SerializedNode | undefined> = {
    active: () => others.find(other => tagsOf(other).includes("active")),
    source: () => others.find(other => source !== undefined && nameOf(other) === nameOf(source)),
    previous: () => others.filter(other => createdOf(other) < createdOf(version))
      .sort((first, second) => createdOf(second) - createdOf(first)).at(0),
  };
  for (const rule of rules) {
    const base = Object.hasOwn(found, rule) ? found[rule]() : undefined;
    if (base) {
      return base;
    }
  }
  return undefined;
}
