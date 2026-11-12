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

// Reads the entities under an entity homepage one page at a time, from the pagination servlet,
// such as `/Submissions.paginate.json`. PaginationServlet, in the search/impl module, lists every
// parameter it takes.


import { type AuthenticatedFetch } from "../reLogin";
import { readJson, RequestError } from "../requestFailure";

// One entity as JSON. Its properties depend on its type, and usually include the node's `@path`
// and `@name`.
export type EntityRow = Record<string, unknown>;

// A condition on one property, such as `{ name: "status", value: "draft" }`. The server ORs the
// conditions that share a group, and ANDs the groups and the conditions without one.
export interface PropertyFilter {
  name: string;
  // A value starting with `@` names somebody, and the server resolves it: `@me` is the person asking
  value: string;
  // One of the servlet's comparators, such as `<>`, `ILIKE` or `IS NULL`; `=` if absent
  comparator?: string;
  // Any label the conditions to OR share; absent for a condition that stands alone
  group?: string;
}

// Conditions on a descendant node. Only the entities with a descendant of this type that meets the
// conditions are listed, such as the submissions with a `sub:Review` by the person asking.
export interface DescendantFilter {
  // A node type name, such as `sub:Review`
  type: string;
  filters: PropertyFilter[];
}

// One page of entities under a homepage to ask for, filtered and sorted if the request says so.
export interface PaginationRequest {
  // The path of the entity homepage, such as "/Submissions"
  homepage: string;
  // How many matches to skip; 0 if absent
  offset?: number;
  // How many matches to return; 10 if absent, and never more than the server's maximum of 1000
  limit?: number;
  // The entity property to order by; `jcr:created` if absent
  sortBy?: string;
  // Whether to order from the largest value down
  descending?: boolean;
  // Conditions on the entities' own properties
  filters?: PropertyFilter[];
  // Conditions on a descendant node of the entities
  childFilter?: DescendantFilter;
  // A full text search term, in the JCR full text syntax; absent or empty for no search
  fullText?: string;
  // Extra selectors for each entity's JSON, such as "1" to include its children
  resourceSelectors?: string;
  // Any text, which the response carries back, to match it with this request
  req?: string;
}

// One page of results from the pagination servlet.
export interface PaginatedPage {
  rows: EntityRow[];
  // The offset and limit the server used
  offset: number;
  limit: number;
  // How many rows this page holds
  returnedrows: number;
  // The total number of matches; a lower bound if totalIsApproximate is set
  totalrows: number;
  // Whether the server stopped counting the matches before reaching the end
  totalIsApproximate: boolean;
  // The request's `req`; absent if it sent none
  req?: string;
}

function appendFilters(params: URLSearchParams, prefix: string, filters: PropertyFilter[]): void {
  // The servlet takes either no group parameter, or one for every filter
  const grouped = filters.some(filter => filter.group != undefined);
  filters.forEach(filter => {
    params.append(`${prefix}Name`, filter.name);
    params.append(`${prefix}Comparator`, filter.comparator ?? "=");
    params.append(`${prefix}Value`, filter.value);
    if (grouped) {
      params.append(`${prefix}Group`, filter.group ?? "");
    }
  });
}

// An empty string means "not requested", the same as an absent value.
function nonEmpty(value: string | undefined): string | undefined {
  return value === "" ? undefined : value;
}

// Fetches one page of entities from the pagination servlet. It rejects with a RequestError
// carrying the status if the server refuses, and otherwise with whatever failed.
// describeRequestFailure words either for the person using the UI.
export async function fetchEntityPage(
  fetchUtil: AuthenticatedFetch, request: PaginationRequest): Promise<PaginatedPage> {
  // Every scalar of the request under its servlet parameter name; unset ones are skipped
  const scalars = {
    offset: String(request.offset ?? 0),
    limit: request.limit?.toString(),
    sortBy: nonEmpty(request.sortBy),
    descending: request.descending ? "true" : undefined,
    filter: nonEmpty(request.fullText),
    childType: request.childFilter?.type,
    resourceSelectors: nonEmpty(request.resourceSelectors),
    req: nonEmpty(request.req),
  };
  const params = new URLSearchParams();
  Object.entries(scalars).forEach(([name, value]) => {
    if (value != undefined) {
      params.set(name, value);
    }
  });
  appendFilters(params, "field", request.filters ?? []);
  appendFilters(params, "childField", request.childFilter?.filters ?? []);
  const response = await fetchUtil(`${request.homepage}.paginate.json?${params.toString()}`);
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return await readJson<PaginatedPage>(response);
}
