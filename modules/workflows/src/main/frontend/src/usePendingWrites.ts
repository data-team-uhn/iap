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

import { useEffect, useRef, useState } from "react";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";
import type ElementRegistry from "diagram-js/lib/core/ElementRegistry";

// What has been typed and not yet written: whether the name has, and which parameters by name.
export interface Pending {
  name: boolean;
  parameters: readonly string[];
}

const NOTHING: Pending = { name: false, parameters: [] };

// Ahead of the listeners subscribed at the default priority. Those are the selection letting go of a
// removed element, and the panel moving on to the next selection.
export const FIRST_PRIORITY = 1500;

// What a panel has typed into one element and not yet written. Selecting another element need not leave the
// field being typed in, so what is pending is handed to `write` as the selection changes. Nothing is written
// once the element is going: its canvas destroyed or cleared, or the element removed, as by undoing its
// creation.
export function usePendingWrites(viewer: BaseViewer, element: Element, write: (pending: Pending) => void) {
  const [ pending, setPending ] = useState<Pending>(NOTHING);
  const gone = useRef(false);

  const writePending = useRef<() => void>(() => undefined);
  useEffect(() => {
    writePending.current = () => {
      if (!pending.name && pending.parameters.length === 0) {
        return;
      }
      setPending(NOTHING);
      if (!gone.current && viewer.get<ElementRegistry>("elementRegistry").get(element.id) === element) {
        write(pending);
      }
    };
  });

  // Replacing an element keeps its id, so the panel is handed the replacement without remounting
  useEffect(() => {
    gone.current = false;
    const leave = () => {
      gone.current = true;
    };
    const removed = (event: { element: Element }) => {
      if (event.element === element) {
        leave();
      }
    };
    const selectionChanged = () => writePending.current();
    viewer.on([ "diagram.destroy", "diagram.clear" ], FIRST_PRIORITY, leave);
    viewer.on([ "shape.remove", "connection.remove" ], FIRST_PRIORITY, removed);
    viewer.on("selection.changed", FIRST_PRIORITY, selectionChanged);
    return () => {
      viewer.off([ "diagram.destroy", "diagram.clear" ], leave);
      viewer.off([ "shape.remove", "connection.remove" ], removed);
      viewer.off("selection.changed", selectionChanged);
    };
  }, [ viewer, element ]);

  return {
    pending,
    holdName: () => setPending(held => ({ ...held, name: true })),
    releaseName: () => setPending(held => ({ ...held, name: false })),
    holdParameter: (name: string) => setPending(held => held.parameters.includes(name) ? held
      : { ...held, parameters: [ ...held.parameters, name ] }),
    releaseParameter: (name: string) => setPending(held =>
      ({ ...held, parameters: held.parameters.filter(each => each !== name) })),
  };
}
