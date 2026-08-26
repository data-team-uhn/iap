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

// The components contributed as the actions available on one kind of thing: the buttons a page
// offers for a workflow version, a submission, a review. A page renders whatever it is handed,
// which is what lets a module add an action to another module's page by shipping an `ext:Extension`
// and nothing else.

import { type ComponentType } from "react";

import { loadExtensions } from "@iap/ui-extension/extensionManager";

// An action component receives whatever the page it appears on passes: the thing being acted on,
// and a way to tell the page that something about it changed. The props are the page's contract
// with its actions, so they are typed at each call site rather than here.
export type ActionComponent = ComponentType<Record<string, unknown>>;

// The resolved components, per extension point, and the in-flight request for the ones being
// resolved: an extension point is fetched once per page load, however many components ask for it.
const actions = new Map<string, ActionComponent[]>();
const requests = new Map<string, Promise<ActionComponent[]>>();

// The action components registered on an extension point, in the order the repository lists them
// (by `defaultOrder`).
//
// A broken extension is dropped by the loader rather than taking the others with it, and a failure
// to reach the extension point at all resolves to no actions: a page whose action bar cannot be
// built still displays the thing itself, which is the part its reader came for.
//
// @param extensionPoint the extension point node name, e.g. "WorkflowVersionActions"
// @return the components to render, empty if the point has none or could not be read
export async function getActions(extensionPoint: string): Promise<ActionComponent[]> {
  const resolved = actions.get(extensionPoint);
  if (resolved) {
    return resolved;
  }
  const pending = requests.get(extensionPoint);
  if (pending) {
    return pending;
  }
  const request = loadExtensions(extensionPoint)
    .then(extensions => {
      const components = extensions
        .map(extension => extension["ext:render"] as ActionComponent | undefined)
        .filter((component): component is ActionComponent => component != undefined);
      actions.set(extensionPoint, components);
      return components;
    })
    .catch((error: unknown) => {
      console.error(`Failed to resolve the ${extensionPoint} actions`, error);
      return [];
    })
    .finally(() => requests.delete(extensionPoint));
  requests.set(extensionPoint, request);
  return request;
}

// Forgets what has been resolved so far. For tests, which would otherwise inherit one another's
// extension points.
export function clearActions(): void {
  actions.clear();
  requests.clear();
}
