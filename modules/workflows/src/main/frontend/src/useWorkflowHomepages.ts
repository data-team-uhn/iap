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

import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readNode } from "@iap/frontend-commons/useNode";

import { homepagesFrom, WORKFLOWS_ROOT, type WorkflowHomepage } from "./workflowModel";

// Cached for the life of the session: every console URL below /admin/workflows is resolved against
// this list, so asking once per navigation would be wasteful. Homepages change only when a bundle
// installs or is removed — a restart, hence a new session — so this cache is never stale. An
// in-flight request is shared, so concurrent page mounts ask the server only once.
let discovered: WorkflowHomepage[] | null = null;
let discovery: Promise<WorkflowHomepage[]> | null = null;

// The homepages the current user may list workflows from, the queried one first — asked of
// /Workflows (which always exists) and answered with every homepage the user can read, so a
// deployment that adds one (the platform's own system workflows, another location's) needs nothing
// configured here. A failed ask falls back to the one homepage everybody has, rather than to nothing.
export function loadWorkflowHomepages(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepage[]> {
  if (discovered) {
    return Promise.resolve(discovered);
  }
  discovery ??= readNode(fetchUtil, WORKFLOWS_ROOT, "homepages")
    .then(homepagesFrom)
    .catch((error: unknown) => {
      console.error("Failed to discover the workflow homepages; listing the default one only", error);
      return [ { path: WORKFLOWS_ROOT, title: "Workflows" } ];
    })
    .then(homepages => discovered = homepages)
    .finally(() => discovery = null);
  return discovery;
}

// Forgets the discovery, so that the next ask goes to the server. For tests, and for a caller that
// has reason to believe the set of homepages has changed under it.
export function forgetWorkflowHomepages(): void {
  discovered = null;
  discovery = null;
}

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
