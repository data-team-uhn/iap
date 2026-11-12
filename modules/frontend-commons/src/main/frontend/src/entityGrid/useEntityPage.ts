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


import { useAuthenticatedFetch } from "../reLogin";
import { describeRequestFailure } from "../requestFailure";
import { type DescendantFilter, type EntityRow, type PropertyFilter, fetchEntityPage } from "./pagination";

import type { EntityGridConfig } from "./registry";
import type { GridPaginationModel } from "@mui/x-data-grid-pro";

export interface EntityPageRequest {
  // The entity type's grid configuration; undefined to read nothing
  config?: EntityGridConfig;
  // The path of the homepage to list from, when it is not the config's own
  homepage?: string;
  paginationModel: GridPaginationModel;
  // The entity property to order by; the server's own order if undefined
  sortBy?: string;
  descending: boolean;
  // The caller's conditions, ANDed with the user's columnFilters
  filters?: PropertyFilter[];
  childFilter?: DescendantFilter;
  columnFilters: PropertyFilter[];
  // The full text search; empty for none
  fullText: string;
  // Reads the page again whenever it changes
  refreshToken: number;
}

export interface EntityPage {
  rows: EntityRow[];
  // The total number of matches
  rowCount: number;
  // Whether the server stopped counting matches early, so that rowCount is a lower bound
  approximate: boolean;
  loading: boolean;
  // Why the page could not be read, worded for the person using the UI; undefined after a success
  error?: string;
  // Reads the page again with the same request
  retry: () => void;
}

// One page of entities from the pagination servlet, read again whenever the request changes. A
// response that arrives after a newer request was made is dropped.
export default function useEntityPage(request: EntityPageRequest): EntityPage {
  const { config, homepage, paginationModel, sortBy, descending, filters, childFilter, columnFilters, fullText,
    refreshToken } = request;
  const [rows, setRows] = useState<EntityRow[]>([]);
  const [rowCount, setRowCount] = useState(0);
  const [approximate, setApproximate] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const fetchUtil = useAuthenticatedFetch();

  // The filters are usually new objects on every render, so the effect depends on their content,
  // not their identity. A new homepage reads the page again too.
  const filterKey = JSON.stringify([filters, childFilter, columnFilters, homepage]);

  useEffect(() => {
    if (!config) {
      return;
    }
    let cancelled = false;
    // The flag turns on in the same effect that starts the request it tracks
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    fetchEntityPage(fetchUtil, {
      homepage: homepage ?? config.homepage,
      offset: paginationModel.page * paginationModel.pageSize,
      limit: paginationModel.pageSize,
      sortBy,
      descending,
      filters: [...filters ?? [], ...columnFilters],
      childFilter,
      fullText: fullText || undefined,
      resourceSelectors: config.children?.selectors,
    }).then(page => {
      if (!cancelled) {
        setRows(page.rows);
        setRowCount(page.totalrows);
        setApproximate(page.totalIsApproximate);
        setError(undefined);
      }
    }).catch((e: unknown) => {
      if (!cancelled) {
        setRows([]);
        setRowCount(0);
        setApproximate(false);
        setError(describeRequestFailure(e));
      }
    }).finally(() => {
      if (!cancelled) {
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [config, fetchUtil, paginationModel, sortBy, descending, filterKey, fullText, retryCount, refreshToken]);

  return { rows, rowCount, approximate, loading, error, retry: () => setRetryCount(count => count + 1) };
}
