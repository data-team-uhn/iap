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

import { type Choice } from "@iap/conditions/conditionModel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readJson } from "@iap/frontend-commons/requestFailure";
import { textValuesOf } from "@iap/frontend-commons/serializedNode";

import { type ItemChoices } from "./conditionModel";

import type { QuestionIndex } from "./schemaVersionTreeModel";

interface OfferedOptions {
  options: Choice[];
}

// The options the version's questions take from a path, by that path. The server gives the same list the submitter
// and a model reading the documents get, so a condition compares with values an answer can really hold. Each path is
// read once, through one of the questions that name it. A path that cannot be read gives no options.
export function useOptionsFrom(index: QuestionIndex): ItemChoices {
  const doFetch = useAuthenticatedFetch();
  const [ items, setItems ] = useState<ItemChoices>({});
  // One "path, question" line per path, so the effect runs again only when they change
  const sources = [ ...new Map(index.questions.flatMap(question => textValuesOf(question.optionsFrom)
    .slice(0, 1).map(path => [ path, String(question["@path"]) ] as const))) ]
    .map(([ path, question ]) => `${path}\t${question}`).sort().join("\n");
  useEffect(() => {
    let cancelled = false;
    void Promise.all(sources.split("\n").filter(line => line !== "").map(async line => {
      const [ path, question ] = line.split("\t");
      try {
        const response = await doFetch(`${question}.offeredOptions.json`);
        const options = response.ok ? (await readJson<OfferedOptions>(response)).options : [];
        return [ path, options.map(({ value, label }) => ({ value, label })) ] as const;
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
  }, [ sources, doFetch ]);
  return items;
}
