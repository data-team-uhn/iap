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

import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { useGroupSelection } from "../useGroupSelection";
import { useSelection } from "../useSelection";
import { CountPill } from "./badges";
import CollectionNode from "./CollectionNode";
import ExpandCaret from "./ExpandCaret";
import { rowButtonProps } from "./rowButton";
import TriStateCheckbox from "./TriStateCheckbox";

import type { VisibleDatabase } from "../useCatalogueFilter";
import type { ExpansionApi } from "../useExpansion";

interface DatabaseNodeProps {
  entry: VisibleDatabase;
  expansion: ExpansionApi;
  searching: boolean;
}

function count(value: number, noun: string): string {
  return `${String(value)} ${value === 1 ? noun : `${noun}s`}`;
}

/** One source system, and the collections under it. */
export default function DatabaseNode({ entry, expansion, searching }: DatabaseNodeProps) {
  const { database, collections, shownFieldCount } = entry;
  const visibleKeys = useMemo(
    () => collections.flatMap(collection => collection.fields.map(field => field.key)),
    [ collections ]);
  const { selectedCount, state, setAll } = useGroupSelection(visibleKeys);
  const { readOnly } = useSelection();
  const open = expansion.isDatabaseOpen(database.identifier, searching);

  return (
    <Box>
      <Box
        {...rowButtonProps(() => { expansion.toggle(database.identifier); })}
        aria-expanded={open}
        aria-label={`${database.label} database`}
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1.25,
          px: { xs: 1.5, md: 2.25 },
          py: 1.5,
          cursor: "pointer",
          borderBottom: 1,
          borderColor: "divider",
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        <ExpandCaret open={open} />
        <TriStateCheckbox
          state={state}
          onChange={setAll}
          disabled={readOnly}
          aria-label={`Select all fields in ${database.label}`}
        />
        <Typography
          variant="subtitle2"
          component="span"
          sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
        >
          {database.label}
        </Typography>
        {/* Both totals in one pill. During a search each is shown against the whole, so the pill
            says what was found and what the source actually holds */}
        <CountPill hideOnMobile>
          {searching
            ? `${String(shownFieldCount)} of ${count(database.fieldCount, "field")} · `
              + `${String(collections.length)} of ${count(database.collections.length, "collection")}`
            : `${count(database.fieldCount, "field")} · `
              + count(database.collections.length, "collection")}
        </CountPill>
        {database.description && (
          <Typography
            variant="caption"
            component="span"
            sx={{ display: { xs: "none", md: "block" }, color: "text.secondary", minWidth: 0,
              overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
          >
            {database.description}
          </Typography>
        )}
        <Box sx={{ flex: 1 }} />
        {selectedCount > 0 && <CountPill accent>{`${String(selectedCount)} selected`}</CountPill>}
      </Box>
      {open && (
        <Box role="group" aria-label={`${database.label} collections`}>
          {collections.map(collection => (
            <CollectionNode
              key={collection.key}
              collection={collection}
              searching={searching}
              open={expansion.isCollectionOpen(collection.key, searching, collection.fields.length)}
              onToggleOpen={expansion.toggle}
            />
          ))}
        </Box>
      )}
    </Box>
  );
}
