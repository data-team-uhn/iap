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
import { describeRequestFailure } from "@iap/frontend-commons/requestFailure";

import { loadWorkflow, type WorkflowSummary } from "./workflowModel";

// One workflow and its versions: what its page and its versions' pages show.
export function useWorkflow(path: string) {
  const fetchUtil = useAuthenticatedFetch();
  const [ workflow, setWorkflow ] = useState<WorkflowSummary>();
  const [ loadError, setLoadError ] = useState<string>();

  const reload = useCallback((): Promise<void> =>
    loadWorkflow(fetchUtil, path)
      .then(loaded => {
        setWorkflow(loaded);
        setLoadError(undefined);
      })
      .catch((error: unknown) => {
        setLoadError(describeRequestFailure(error));
      }), [ fetchUtil, path ]);

  useEffect(() => {
    void reload();
  }, [ reload ]);

  return { workflow, loading: workflow === undefined && loadError === undefined, loadError, reload };
}
