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

import { type AuthenticatedFetch, useAuthenticatedFetch } from "../reLogin";
import { describeRequestFailure } from "../requestFailure";
import { type Choice } from "./choices";

/** Where the choices of a search come from. */
export interface ChoiceSource {
  /** Names what the source offers: two sources with the same key offer the same choices. */
  key: string;
  /** Every choice the source offers. */
  search: (doFetch: AuthenticatedFetch) => Promise<Choice[]>;
  /** How a stored value is labelled when no choice holds it. */
  labelOf: (value: string) => string;
}

interface Found {
  key: string;
  choices: Choice[];
  loadError?: string;
}

/** The choices a source offers. */
export function useChoices(source: ChoiceSource) {
  const doFetch = useAuthenticatedFetch();
  // A caller builds a new source on every render; only a new key reads again
  const [ wanted, setWanted ] = useState(source);
  if (wanted.key !== source.key) {
    setWanted(source);
  }
  const [ found, setFound ] = useState<Found>();

  useEffect(() => {
    let current = true;
    wanted.search(doFetch)
      .then(choices => {
        if (current) {
          setFound({ key: wanted.key, choices });
        }
      })
      .catch((error: unknown) => {
        if (current) {
          setFound({ key: wanted.key, choices: [], loadError: describeRequestFailure(error) });
        }
      });
    return () => {
      current = false;
    };
  }, [ doFetch, wanted ]);

  const shown = found?.key === source.key ? found : undefined;
  return { choices: shown?.choices ?? [], loading: shown === undefined, loadError: shown?.loadError };
}
