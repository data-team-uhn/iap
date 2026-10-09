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

import { RequestError } from "../requestFailure";
import {
  candidateOf, type ContentField, type ReferenceCandidate, referenceQuery, type SerializedNode,
} from "./fieldsModel";

import type { AuthenticatedFetch } from "../reLogin";

// The nodes a reference field may point at, as the search finds them, sorted by what they are offered as; none for a
// field naming no type of node
export async function readCandidates(doFetch: AuthenticatedFetch,
  field: Pick<ContentField, "referenceType" | "referenceRoot">): Promise<ReferenceCandidate[]> {
  const query = referenceQuery(field);
  if (!query) {
    return [];
  }
  const response = await doFetch(`/search.json?${new URLSearchParams({ query, limit: "1000" }).toString()}`);
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  const { rows } = await response.json() as { rows?: SerializedNode[] };
  return (rows ?? [])
    .map(row => candidateOf(row, field.referenceRoot))
    .filter((candidate): candidate is ReferenceCandidate => candidate !== undefined)
    .sort((a, b) => a.label.localeCompare(b.label));
}
