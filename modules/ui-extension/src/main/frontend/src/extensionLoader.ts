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

import { loadExtensions } from "./extensionManager";

export interface ExtensionLoader {
  // Loads every extension declared on the point, once. Never rejects: a failed load is logged, and the
  // next call tries again.
  load: () => Promise<void>;
  // A hook that is false until the load settles, then true however it settled
  useLoaded: () => boolean;
}

/**
 * Loads the extensions of a point whose assets register themselves as they are evaluated. What
 * `loadExtensions` hands back is discarded: the assets have already put their contribution where
 * it is read.
 *
 * @param point the extension point, a name under `/apps/iap/ExtensionPoints/` or a repository path
 * @param what what the point's extensions are, in plural, such as "answer components", for the log
 * @return the loader of that point, keeping its result for the life of the page
 */
export function createExtensionLoader(point: string, what: string): ExtensionLoader {
  let request: Promise<void> | null = null;
  let loaded = false;

  const load = async (): Promise<void> => {
    if (loaded) {
      return;
    }
    request ??= loadExtensions(point)
      .then(() => {
        loaded = true;
      })
      .catch((e: unknown) => {
        console.error(`Failed to load the ${what}`, e);
      })
      .finally(() => {
        request = null;
      });
    return request;
  };

  const useLoaded = (): boolean => {
    const [ ready, setReady ] = useState(loaded);
    useEffect(() => {
      void load().finally(() => setReady(true));
    }, []);
    return ready;
  };

  return { load, useLoaded };
}
