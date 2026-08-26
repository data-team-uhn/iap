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

import type { EntityGridColumn, EntityGridConfig } from "./registry";
import type { GridPaginationModel, GridSortModel } from "@mui/x-data-grid-pro";

export interface EntityPageRequest {
  config?: EntityGridConfig;
  // The homepage to list from, when it is not the config's own
  homepage?: string;
  columns: EntityGridColumn[];
  paginationModel: GridPaginationModel;
  sortModel: GridSortModel;
  filters?: PropertyFilter[];
  childFilter?: DescendantFilter;
  columnFilters: PropertyFilter[];
  fullText: string;
  // Any change reads the page again
  refreshToken: number;
}

export interface EntityPage {
  rows: EntityRow[];
  rowCount: number;
  // Whether the server stopped counting matches early: the row count is then a lower bound
  approximate: boolean;
  loading: boolean;
  error?: string;
  // Reads the page again with unchanged parameters
  retry: () => void;
}

// One page of entities from the pagination servlet, read again whenever what it asks for changes.
export default function useEntityPage(request: EntityPageRequest): EntityPage {
  const { config, homepage, columns, paginationModel, sortModel, filters, childFilter, columnFilters, fullText,
    refreshToken } = request;
  const [rows, setRows] = useState<EntityRow[]>([]);
  const [rowCount, setRowCount] = useState(0);
  const [approximate, setApproximate] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const fetchUtil = useAuthenticatedFetch();

  // The props holding the fixed filters are typically fresh objects on every render, so effects
  // depend on their content instead of their identity. The homepage belongs here too: a caller
  // that discovers it asynchronously hands it over once the discovery lands, and that has to
  // re-fetch.
  const filterKey = JSON.stringify([filters, childFilter, columnFilters, homepage]);

  useEffect(() => {
    if (!config) {
      return;
    }
    let cancelled = false;
    // The flag intentionally turns on synchronously with the request it tracks; deriving it
    // from a request key instead proved racy against the grid's own debounced model updates
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    const sortColumn = sortModel[0] && columns.find(column => column.field === sortModel[0].field);
    fetchEntityPage(fetchUtil, {
      homepage: homepage ?? config.homepage,
      offset: paginationModel.page * paginationModel.pageSize,
      limit: paginationModel.pageSize,
      sortBy: sortColumn ? sortColumn.sortProperty ?? sortColumn.field : undefined,
      descending: sortModel[0]?.sort === "desc",
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
  }, [columns, config, fetchUtil, paginationModel, sortModel, filterKey, fullText, retryCount,
    refreshToken]);

  return { rows, rowCount, approximate, loading, error, retry: () => setRetryCount(count => count + 1) };
}
