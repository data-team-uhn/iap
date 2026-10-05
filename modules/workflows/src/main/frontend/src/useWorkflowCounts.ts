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

import { useEffect, useState } from "react";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { loadWorkflowCounts, type WorkflowHomepageCount } from "./workflowModel";

// How many workflows each homepage holds: what the dashboard widget shows.
export function useWorkflowCounts() {
  const fetchUtil = useAuthenticatedFetch();
  const [ counts, setCounts ] = useState<WorkflowHomepageCount[]>();

  useEffect(() => {
    let cancelled = false;
    void loadWorkflowCounts(fetchUtil).then(counted => {
      if (!cancelled) {
        setCounts(counted);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ fetchUtil ]);

  return { counts: counts ?? [], loading: counts === undefined };
}
