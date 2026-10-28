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

// Where a node lists the tags it carries: those placed on it, then those each propagation phase
// materialized at commit time, as the server's Taggable.getEffectiveTagNames reads them
const TAG_PROPERTIES = [ "tags", "inheritedTags", "computedTags", "aggregatedTags" ];

// Whether a node carries a tag: placed on it, inherited from an ancestor, computed from its own
// content, or aggregated up from the content below it
export function carriesTag(node: SerializedNode | undefined, tag: string): boolean {
  return TAG_PROPERTIES.some(property => stringsOf(node?.[property]).includes(tag));
}
