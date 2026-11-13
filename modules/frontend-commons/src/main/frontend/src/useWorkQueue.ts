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

// Hands work to a queue, and returns the work's own promise
export type Enqueue = <T>(start: () => Promise<T>) => Promise<T>;

// Runs work one piece at a time, in the order it was handed over, such as saves that would conflict
// if they reached the server together. Each piece starts once all the work before it has settled. A
// piece that fails does not hold up the next.
//
// Sample usage:
// const enqueue = useWorkQueue();
// ...
// <Editor queueSave={enqueue} />
// <Button onClick={() => enqueue(submit)}>Submit</Button>
//
export function useWorkQueue(): Enqueue {
  const last = useRef<Promise<unknown>>(Promise.resolve());

  return useCallback<Enqueue>(start => {
    const run = () => start();
    const work = last.current.then(run, run);
    last.current = work;
    return work;
  }, []);
}
