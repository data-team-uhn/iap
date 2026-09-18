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

import { CATCHING_CHANGED_EVENT, CATCHING_PATH } from "./caughtMailModel";

// Check whether mail is being caught.
//
// A plain fetch, not useAuthenticatedFetch. This runs before sign-in, and that helper always offers
// to sign in, which is the wrong behavior for a guest.
export default function useCatching(): boolean {
  const [ catching, setCatching ] = useState(false);

  const ask = useCallback((applies: () => boolean) => {
    fetch(CATCHING_PATH)
      .then(response => (response.ok ? response.json() : null))
      .then((body: { catching?: unknown } | null) => {
        if (applies()) {
          setCatching(body?.catching === true);
        }
      })
      .catch(() => {
        // Deliberately nothing: the warning is not critical enough to deface the entire app when it can't be loaded.
      });
  }, []);

  useEffect(() => {
    let current = true;
    const applies = () => current;
    ask(applies);
    const again = () => ask(applies);
    window.addEventListener(CATCHING_CHANGED_EVENT, again);
    return () => {
      current = false;
      window.removeEventListener(CATCHING_CHANGED_EVENT, again);
    };
  }, [ ask ]);

  return catching;
}
