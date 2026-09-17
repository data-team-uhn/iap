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

// Whether mail is being caught, for the banner that says so.
//
// A plain fetch, not useAuthenticatedFetch. This runs before sign-in — somebody waiting for a
// password-reset mail has no session — and that helper reads a failure as an expired one and offers
// to sign in again, which is the wrong answer for a component asking a question anybody may ask.
//
// A failure is silence. This banner exists to add a warning, so a warning nobody could fetch is the
// same as no warning; putting an error on every page because one endpoint was unreachable would be
// worse than the thing it reports.
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
        // Deliberately nothing: see above.
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
