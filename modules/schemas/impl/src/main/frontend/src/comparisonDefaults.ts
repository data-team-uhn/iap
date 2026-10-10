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

// Which version another is compared with when none is named: the first of the rules, in their order, that finds one
// that is not the version itself. A rule is a name: `active` for the active version, `source` for the one it was
// copied from, `previous` for the one created last before it; a name not known finds nothing. No React, no fetch.

// Where a reference points, whether given as a path, an identifier, or the node it was dereferenced to
const pointsAt = (value: unknown, version: SerializedNode): boolean => (isNode(value)
  ? pathOf(value) === pathOf(version)
  : value === pathOf(version) || value === version["jcr:uuid"]);

// The version a version's links say it was copied from, among the schema's versions
export function sourceOf(links: SerializedNode | undefined, versions: SerializedNode[]): SerializedNode | undefined {
  const link = childrenOf(links ?? {}).find(child => {
    const type = isNode(child.type) ? pathOf(child.type) : String(child.type);
    return type.endsWith("/copiedFrom");
  });
  return link && versions.find(version => pointsAt(link.reference, version));
}

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
