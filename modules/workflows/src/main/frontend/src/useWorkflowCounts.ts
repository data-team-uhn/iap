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

import { fetchEntityPage } from "@iap/frontend-commons/entityGrid/pagination";
import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { loadWorkflowHomepages } from "./useWorkflowHomepages";

import type { WorkflowHomepageCount } from "./workflowModel";

// One count per homepage, asked of the pagination endpoint with a page of no rows — exactly a count
// and nothing else, which is what a summary needs. Counted independently and settled rather than
// joined, so a homepage that can't be counted (unreadable, or a failed request) loses only its own
// number instead of blanking every other homepage's count too.
export function loadWorkflowCounts(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepageCount[]> {
  return loadWorkflowHomepages(fetchUtil).then(homepages => Promise.allSettled(
    homepages.map(homepage => fetchEntityPage(fetchUtil, { homepage: homepage.path, limit: 0 }))
  ).then(answers => answers.map((answer, index) => {
    const homepage = homepages[index];
    if (answer.status === "rejected") {
      console.error(`Failed to count the workflows in ${homepage.path}`, answer.reason);
      return { ...homepage, atLeast: false };
    }
    return { ...homepage, count: answer.value.totalrows, atLeast: answer.value.totalIsApproximate };
  })));
}

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
