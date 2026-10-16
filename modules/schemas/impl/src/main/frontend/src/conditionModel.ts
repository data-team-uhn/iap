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

// The conditions of two versions of a schema, in a form the comparison can tell apart. No React, no fetch.

import { isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

import { nameOf, pathOf } from "./schemaModel";
import { type QuestionIndex, strings } from "./schemaVersionTreeModel";

// A condition in a form two versions can compare: what it stores, without the repository's bookkeeping. The questions
// it compares the answers of go by their identifiers in the version, which a copy keeps and a move does not change. The
// repository's own identifiers would not do, since a copy changes them. A question whose identifier another shares goes
// by its path within the version. Keys are sorted, so that the order they were written in does not count.
export function conditionKeyOf(condition: SerializedNode, index: QuestionIndex, version: string): string {
  const keyOf = (question: SerializedNode) =>
    (index.questions.filter(other => nameOf(other) === nameOf(question)).length === 1
      ? nameOf(question) : pathOf(question).slice(version.length + 1));
  const canonical = (node: SerializedNode): SerializedNode => Object.fromEntries(Object.entries(node)
    .filter(([ key ]) => !/^(jcr:|sling:|@)/.test(key))
    .sort(([ first ], [ second ]) => first.localeCompare(second))
    .map(([ key, value ]) => {
      if (isNode(value)) {
        return [ key, canonical(value) ];
      }
      if (key === "value" && node.source === "answer") {
        return [ key, strings(value).map(reference => {
          const question = index.find(reference);
          return question ? keyOf(question) : reference;
        }) ];
      }
      return [ key, value ];
    }));
  return JSON.stringify(canonical(condition));
}
