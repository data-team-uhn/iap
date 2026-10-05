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

import { loadWorkflowHomepages, type WorkflowHomepage } from "./workflowModel";

// The homepages workflows live in: what the console routes by and the listing shows a tab for.
export function useWorkflowHomepages() {
  const fetchUtil = useAuthenticatedFetch();
  const [ homepages, setHomepages ] = useState<WorkflowHomepage[]>();

  useEffect(() => {
    let cancelled = false;
    void loadWorkflowHomepages(fetchUtil).then(discovered => {
      if (!cancelled) {
        setHomepages(discovered);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ fetchUtil ]);

  return { homepages: homepages ?? [], loading: homepages === undefined };
}
