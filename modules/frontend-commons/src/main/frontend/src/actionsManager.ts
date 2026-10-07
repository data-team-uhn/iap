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

// Resolves the action buttons a page renders for one kind of thing (a workflow version, a submission,
// a review). A module adds an action to another module's page by shipping an `ext:Extension`, with
// no code change to that page.

import { type ComponentType } from "react";

import { loadExtensions } from "@iap/ui-extension/extensionManager";

// A contributed action component. Each page defines its own props contract for its actions —
// typically the thing being acted on, and a way to report back — so props are typed at the call
// site, not here.
export type ActionComponent = ComponentType<Record<string, unknown>>;

// An action as its extension contributes it: its component, and the places it is shown in, if it names any
interface ContributedAction {
  component: ActionComponent;
  places?: string[];
}

// The resolved actions, per extension point, and the in-flight request for the ones being
// resolved: an extension point is fetched once per page load, however many components ask for it.
const actions = new Map<string, ContributedAction[]>();
const requests = new Map<string, Promise<ContributedAction[]>>();

// The places an extension names, if any. A repository multi-value property arrives as an array, but a single value
// may arrive bare.
function placesOf(value: unknown): string[] | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  return (Array.isArray(value) ? value : [ value ]).map(String);
}

function contributedTo(extensionPoint: string): Promise<ContributedAction[]> {
  const resolved = actions.get(extensionPoint);
  if (resolved) {
    return Promise.resolve(resolved);
  }
  const pending = requests.get(extensionPoint);
  if (pending) {
    return pending;
  }
  const request = loadExtensions(extensionPoint)
    .then(extensions => {
      const contributed = extensions.flatMap((extension): ContributedAction[] => {
        const component = extension["ext:render"] as ActionComponent | undefined;
        return component ? [ { component, places: placesOf(extension["ext:places"]) } ] : [];
      });
      actions.set(extensionPoint, contributed);
      return contributed;
    })
    .catch((error: unknown) => {
      console.error(`Failed to resolve the ${extensionPoint} actions`, error);
      return [];
    })
    .finally(() => requests.delete(extensionPoint));
  requests.set(extensionPoint, request);
  return request;
}

// The action components registered on an extension point, in the order the repository lists them. An extension
// naming the `ext:places` it is shown in is left out of any other; one naming none is shown everywhere.
//
// Failures are absorbed, not surfaced: a broken extension is skipped, and an unreadable extension
// point yields no actions. A page's action bar failing never hides the page itself.
//
// @param extensionPoint the extension point node name, e.g. "WorkflowVersionActions"
// @param place where the actions are shown, e.g. "versionList", if the page tells its places apart
// @return the components to render, empty if the point has none or could not be read
export async function getActions(extensionPoint: string, place?: string): Promise<ActionComponent[]> {
  return (await contributedTo(extensionPoint))
    .filter(action => !action.places || (place !== undefined && action.places.includes(place)))
    .map(action => action.component);
}

// Resets resolved actions between tests, so one test's extension points don't leak into the next.
export function clearActions(): void {
  actions.clear();
  requests.clear();
}
