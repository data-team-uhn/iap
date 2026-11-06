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

import { useCallback, useRef } from "react";

// Tracks work still running, such as saves, so that a step can wait for it. A step that ends
// editing must not overtake the save of the answer just given.
//
// Sample usage:
// const saves = usePendingWork();
// ...
// <Editor onSaving={saves.track} />
// <Button onClick={() => saves.allDone().then(submit)}>Submit</Button>
//
export function usePendingWork() {
  const pending = useRef(new Set<Promise<unknown>>());

  // Tracks the work until it settles
  const track = useCallback((work: Promise<unknown>) => {
    pending.current.add(work);
    const forget = () => {
      pending.current.delete(work);
    };
    work.then(forget, forget);
  }, []);

  // Resolves once all the work still running has succeeded, and rejects with the first failure. Work
  // that failed before the call is forgotten, and does not count.
  const allDone = useCallback((): Promise<void> =>
    Promise.all([ ...pending.current ]).then(() => undefined), []);

  return { track, allDone };
}
