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

import { useCallback, useEffect, useState } from "react";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { isNode } from "./jsonNode";
import { type SubmissionTask, tasksFrom } from "./taskModel";

// The workflow container, read on its own rather than with the whole submission: the answers are
// large, this is small, and reading it separately is what lets the editor show the same controls as
// the read-only page. References stay as paths, since an instance's names a workflow the reader
// may not read, and `events` says which tasks are the reader's to complete.
const CONTAINER = "/wf:instances.deep.simple.-dereference.events.json";

// What a submission's workflows are waiting for, read again whenever asked.
//
// Failing to read it is quiet: not being able to tell what a request is waiting for means offering
// nothing, which is what an empty list already says. A submission nothing runs on has no container
// at all, and that is the same answer.
export function useSubmissionTasks(path: string): { tasks: SubmissionTask[]; reload: () => Promise<void> } {
  const doFetch = useAuthenticatedFetch();
  const [ tasks, setTasks ] = useState<SubmissionTask[]>([]);

  const read = useCallback(() => doFetch(`${path}${CONTAINER}`)
    .then(response => (response.ok ? response.json() as Promise<unknown> : {}))
    .then(container => tasksFrom(isNode(container) ? container : {}))
    .catch(() => []), [ doFetch, path ]);

  useEffect(() => {
    let cancelled = false;
    void read().then(found => {
      if (!cancelled) {
        setTasks(found);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ read ]);

  const reload = useCallback(() => read().then(setTasks), [ read ]);
  return { tasks, reload };
}
