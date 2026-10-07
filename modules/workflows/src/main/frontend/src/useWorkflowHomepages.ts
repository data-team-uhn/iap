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

import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { readNode } from "@iap/frontend-commons/useNode";

import { homepagesFrom, WORKFLOWS_ROOT, type WorkflowHomepage } from "./workflowModel";

// Kept once discovered: every console URL below /admin/workflows is resolved against this list, so asking
// once per navigation would be wasteful. A homepage added while the page is open appears after a reload. A
// failure is not kept, so the next ask goes to the server again. An in-flight request is shared, so
// concurrent page mounts ask the server only once.
let discovered: WorkflowHomepage[] | null = null;
let discovery: Promise<WorkflowHomepage[]> | null = null;

// The one homepage everybody has, which stands in when the others could not be discovered.
const DEFAULT_HOMEPAGES: WorkflowHomepage[] = [ { path: WORKFLOWS_ROOT, title: "Workflows" } ];

// The homepages the current user may list workflows from, the queried one first — asked of
// /Workflows (which always exists) and answered with every homepage the user can read, so a
// deployment that adds one (the platform's own system workflows, another location's) needs nothing
// configured here. Rejects when the server could not be asked.
function discover(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepage[]> {
  if (discovered) {
    return Promise.resolve(discovered);
  }
  discovery ??= readNode(fetchUtil, WORKFLOWS_ROOT, "homepages")
    .then(homepagesFrom)
    .then(homepages => discovered = homepages)
    .finally(() => discovery = null);
  return discovery;
}

// The homepages, or the one everybody has when they could not be discovered, rather than nothing.
export function loadWorkflowHomepages(fetchUtil: AuthenticatedFetch): Promise<WorkflowHomepage[]> {
  return discover(fetchUtil).catch((error: unknown) => {
    console.error("Failed to discover the workflow homepages; listing the default one only", error);
    return DEFAULT_HOMEPAGES;
  });
}

// Forgets the discovery, so that the next ask goes to the server. For tests, and for a caller that
// has reason to believe the set of homepages has changed under it.
export function forgetWorkflowHomepages(): void {
  discovered = null;
  discovery = null;
}

interface HomepagesState {
  homepages: WorkflowHomepage[];
  // Why they could not be discovered, while the default stands in for them
  loadError?: string;
}

// The homepages workflows live in: what the console routes by and the listing shows a tab for. Where they
// could not be discovered, the default one stands in, with the reason and a way to ask again.
export function useWorkflowHomepages() {
  const fetchUtil = useAuthenticatedFetch();
  const [ state, setState ] = useState<HomepagesState>();

  const load = useCallback(() => discover(fetchUtil).then(
    (homepages): HomepagesState => ({ homepages }),
    (error: unknown): HomepagesState => ({ homepages: DEFAULT_HOMEPAGES, loadError: messageOf(error) }),
  ), [ fetchUtil ]);

  useEffect(() => {
    let cancelled = false;
    void load().then(loaded => {
      if (!cancelled) {
        setState(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ load ]);

  const retry = useCallback(() => load().then(setState), [ load ]);

  return { homepages: state?.homepages ?? [], loading: state === undefined, loadError: state?.loadError, retry };
}
