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

import { createContext, useCallback, useContext } from "react";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { type JcrNode, pathOf } from "./schemaModel";

// How a change made anywhere in a version's tree re-reads the tree
export const ReloadTree = createContext<() => void | Promise<void>>(() => undefined);

// Sends an event to a node of the tree, or to where one is, then re-reads the tree. Resolves with where what the event
// created or moved now is, once the tree shows it.
export function useTreeEvent() {
  const doFetch = useAuthenticatedFetch();
  const reload = useContext(ReloadTree);
  return useCallback(async (node: JcrNode | string, event: string, params?: Record<string, string>) => {
    const path = await sendEvent(doFetch, typeof node === "string" ? node : pathOf(node), event, params);
    await reload();
    return path;
  }, [ doFetch, reload ]);
}
