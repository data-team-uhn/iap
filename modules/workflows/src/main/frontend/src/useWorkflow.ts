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

import { useCallback } from "react";

import { useNode } from "@iap/frontend-commons/useNode";

import { workflowFrom, type JcrNode } from "./workflowModel";

// One workflow and its versions: what its page and its versions' pages show.
//
// One level of children is exactly what the page renders — the definition's own properties and the
// versions under it — so the depth selector both turns on child serialization and stops it there,
// leaving a version's own children (the diagram file, the parsed flow nodes) out of the response
// rather than dragging a whole graph in behind every row. `events` adds what the current user may send
// each of them, which decides the actions offered.
export function useWorkflow(path: string) {
  const parse = useCallback((definition: JcrNode) => workflowFrom(path, definition), [ path ]);
  const { value, loading, loadError, reload } = useNode(path, "1.events", parse);

  return { workflow: value, loading, loadError, reload };
}
