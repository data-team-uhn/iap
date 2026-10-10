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

// Where a part may move to in a draft: where what it would be moved into may hold what it
// is, outside itself, and somewhere other than where it already stands. No React, no fetch.

import { creatableOf } from "@iap/frontend-commons/fields/fieldsModel";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

import { pathOf } from "./schemaModel";
import { partsOf } from "./schemaVersionTreeModel";

// Whether moving a node into a parent, before one of its children or else last, would put it somewhere new
export function isMoveSpot(moving: SerializedNode, parent: SerializedNode, before?: SerializedNode): boolean {
  const path = pathOf(moving);
  const parentPath = pathOf(parent);
  if (parentPath === path || parentPath.startsWith(`${path}/`)
    || !creatableOf(parent).some(type => type.type === moving["jcr:primaryType"])) {
    return false;
  }
  const siblings = partsOf(parent).map(pathOf);
  const at = siblings.indexOf(path);
  const placedAt = before ? siblings.indexOf(pathOf(before)) : siblings.length;
  return at < 0 || (placedAt !== at && placedAt !== at + 1);
}
