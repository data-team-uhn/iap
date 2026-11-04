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

import { type AuthenticatedFetch } from "../reLogin";
import { readJson, RequestError } from "../requestFailure";
import { type SerializedNode } from "../serializedNode";
import { type ChoiceSource } from "./choiceSource";
import { candidateOf, type ReferenceCandidate, referenceQuery, type ReferenceTarget } from "./referenceTargets";

/** The nodes a reference may point at, at most a thousand, sorted by their labels. */
export async function readReferences(doFetch: AuthenticatedFetch, target: ReferenceTarget)
  : Promise<ReferenceCandidate[]> {
  const query = referenceQuery(target);
  if (!query) {
    return [];
  }
  const response = await doFetch(`/search.json?${new URLSearchParams({ query, limit: "1000" }).toString()}`);
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  const { rows } = await readJson<{ rows?: SerializedNode[] }>(response);
  return (rows ?? [])
    .map(row => candidateOf(row, target.root))
    .filter((candidate): candidate is ReferenceCandidate => candidate !== undefined)
    .sort((a, b) => a.label.localeCompare(b.label));
}

/** The nodes a reference may point at, as a source of choices, each stored as its path. */
export const referenceChoices = (target: ReferenceTarget): ChoiceSource => ({
  key: `reference:${JSON.stringify(target)}`,
  search: async doFetch =>
    (await readReferences(doFetch, target)).map(({ path, label }) => ({ value: path, label })),
});
