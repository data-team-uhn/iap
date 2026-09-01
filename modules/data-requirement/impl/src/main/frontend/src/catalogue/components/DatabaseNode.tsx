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

import { useMemo } from "react";

import Typography from "@mui/material/Typography";

import { countOf } from "@iap/frontend-commons/words";

import { CountPill } from "./badges";
import CollectionNode from "./CollectionNode";
import GroupRow from "./GroupRow";

import type { VisibleDatabase } from "../useCatalogueFilter";
import type { ExpansionApi } from "../useExpansion";

interface DatabaseNodeProps {
  entry: VisibleDatabase;
  expansion: ExpansionApi;
  searching: boolean;
}

/** One source system, and the collections under it. */
export default function DatabaseNode({ entry, expansion, searching }: DatabaseNodeProps) {
  const { database, collections, shownFieldCount } = entry;
  const visibleKeys = useMemo(
    () => collections.flatMap(collection => collection.fields.map(field => field.key)),
    [ collections ]);

  return (
    <GroupRow
      label={database.label}
      kind="database"
      keys={visibleKeys}
      open={expansion.isDatabaseOpen(database.identifier)}
      onOpenChange={open => { expansion.setOpen(database.identifier, open); }}
      details={(
        <>
          {/* During a search, each count is shown against the source's total */}
          <CountPill
            hideOnMobile
            label={searching
              ? `${String(shownFieldCount)} of ${countOf(database.fieldCount, "field")} · `
                + `${String(collections.length)} of ${countOf(database.collections.length, "collection")}`
              : `${countOf(database.fieldCount, "field")} · ${countOf(database.collections.length, "collection")}`}
          />
          {database.description && (
            <Typography
              variant="caption"
              component="span"
              noWrap
              sx={{ display: { xs: "none", md: "block" }, minWidth: 0 }}
            >
              {database.description}
            </Typography>
          )}
        </>
      )}
    >
      {collections.map(collection => (
        <CollectionNode
          key={collection.key}
          collection={collection}
          searching={searching}
          open={expansion.isCollectionOpen(collection.key, collection.fields.length)}
          onOpenChange={open => { expansion.setOpen(collection.key, open); }}
        />
      ))}
    </GroupRow>
  );
}
