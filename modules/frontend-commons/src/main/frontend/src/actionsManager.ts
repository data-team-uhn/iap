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

// The action buttons a page shows for one kind of thing, such as a workflow version. A module adds
// an action to another module's page by shipping an `ext:Extension`, and the page's code does not
// change.

import { type ComponentType } from "react";

import { loadExtensions } from "@iap/ui-extension/extensionManager";

// A contributed action component. The page that shows the actions decides their props, such as the
// thing acted on, so they are not typed here.
export type ActionComponent = ComponentType<Record<string, unknown>>;

// An action as its extension contributes it: its component, and the places it is shown in; undefined
// places means everywhere.
interface ContributedAction {
  component: ActionComponent;
  places?: string[];
}

// The loaded actions of each extension point, and the requests still running. An extension point
// that was read is kept for the page load, however many components ask for it, even when it gave no
// actions. A failed read is not kept, and the next call reads the point again.
const actions = new Map<string, ContributedAction[]>();
const requests = new Map<string, Promise<ContributedAction[]>>();

// The places an extension names; undefined if it names none. A multi-valued property arrives as an
// array, but a single value may arrive bare.
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
    .finally(() => requests.delete(extensionPoint));
  requests.set(extensionPoint, request);
  return request;
}

// The action components registered on an extension point, in the order the repository lists them. An
// extension that names `ext:places` is shown only in those places. One naming none is shown everywhere.
//
// An extension that fails to load, or has no `ext:render`, is skipped.
//
// @param extensionPoint the node name of the extension point, such as "WorkflowVersionActions"
// @param place the name a page gives the place where it shows the actions; undefined shows only the
//     actions that name no places
// @return the components to render; empty if the point has none. Rejects if the extension point
//     cannot be read.
export async function getActions(extensionPoint: string, place?: string): Promise<ActionComponent[]> {
  return (await contributedTo(extensionPoint))
    .filter(action => !action.places || (place !== undefined && action.places.includes(place)))
    .map(action => action.component);
}

// Forgets the loaded actions. Only for tests, so that one test's extension points do not reach the next.
export function clearActions(): void {
  actions.clear();
  requests.clear();
}
