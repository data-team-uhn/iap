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

// Client for the pagination servlet, which serves the entities stored under an entity homepage
// (e.g. `/Submissions.paginate.json`) one page at a time. See PaginationServlet in the
// data-model/entities module for the full list of supported parameters.


import { type AuthenticatedFetch } from "../reLogin";
import { RequestError } from "../requestFailure";

// One serialized entity; the exact properties depend on the entity type. Every row carries at
// least the `@path` and `@name` identification of the underlying node.
export type EntityRow = Record<string, unknown>;

// A condition on one property, e.g. `{ name: "status", value: "draft" }`. The comparator
// defaults to `=`; the special value `@me` is resolved server-side to the current user.
// Conditions sharing a group are ORed together by the server; distinct groups (and conditions
// with no group) are ANDed.
export interface PropertyFilter {
  name: string;
  value: string;
  comparator?: string;
  group?: string;
}

// Conditions on a descendant node: only entities having at least one descendant of this type
// matching all the filters are returned, e.g. submissions with a `sub:Review` by the current user.
export interface DescendantFilter {
  type: string;
  filters: PropertyFilter[];
}

// What to request: a page of entities under a homepage, optionally filtered and sorted.
export interface PaginationRequest {
  // The homepage path, e.g. "/Submissions"
  homepage: string;
  // How many matches to skip; 0 by default
  offset?: number;
  // How many matches to return; the server defaults to 10
  limit?: number;
  // The entity property to order by; the server defaults to jcr:created
  sortBy?: string;
  // Reverse the order
  descending?: boolean;
  // Conditions on the entities' own properties
  filters?: PropertyFilter[];
  // Conditions on a descendant node of the entities
  childFilter?: DescendantFilter;
  // A full text search term
  fullText?: string;
  // An opaque identifier echoed back in the response, for matching responses to requests
  req?: string;
}

// One page of results, as returned by the pagination servlet.
export interface PaginatedPage {
  rows: EntityRow[];
  offset: number;
  limit: number;
  returnedrows: number;
  // The total number of matches; a lower bound if totalIsApproximate is set
  totalrows: number;
  // Whether the server stopped counting the matches before reaching the end
  totalIsApproximate: boolean;
  req?: string;
}

function appendFilters(params: URLSearchParams, prefix: string, filters: PropertyFilter[]): void {
  // The group parameter, when sent at all, must be sent once per filter
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

// Fetches one page of entities from the pagination servlet, through the caller's session-aware
// fetch so that a session expiring mid-browse is signed back in rather than losing the page.
//
// @throws RequestError if the server rejects the request, or whatever `fetch` failed with; both
//         are for `describeRequestFailure` to turn into something worth showing a user
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
  return await response.json() as PaginatedPage;
}
