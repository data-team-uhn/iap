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

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type ItemChoices, itemChoicesOf } from "./conditionModel";
import { type QuestionIndex, strings } from "./schemaVersionTreeModel";

// The items the version's questions take their options from, loaded once for each path; none where they cannot be
export function useOptionsFrom(index: QuestionIndex): ItemChoices {
  const doFetch = useAuthenticatedFetch();
  const [ items, setItems ] = useState<ItemChoices>({});
  const paths = [ ...new Set(index.questions.flatMap(question => strings(question.optionsFrom))) ].sort().join("\n");
  useEffect(() => {
    let cancelled = false;
    void Promise.all(paths.split("\n").filter(path => path !== "").map(async path => {
      try {
        const response = await doFetch(`${path}.deep.simple.json`);
        return [ path, response.ok ? itemChoicesOf(await response.json() as Record<string, unknown>) : [] ] as const;
      } catch {
        return [ path, [] ] as const;
      }
    })).then(entries => {
      if (!cancelled) {
        setItems(Object.fromEntries(entries) as ItemChoices);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ paths, doFetch ]);
  return items;
}
