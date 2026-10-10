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

import { useMemo, useState } from "react";

import CompareArrowsIcon from "@mui/icons-material/CompareArrows";
import { Box, Button, Checkbox, Stack, Typography } from "@mui/material";
import { useNavigate } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";

import { createdOf, type JcrNode, labelOf, nameOf, versionsOf } from "./schemaModel";
import SchemaVersionActions from "./SchemaVersionActions";
import { SCHEMA_VERSION_TYPE } from "./schemaVersionGrid";
import { comparisonPageUrl } from "./useSchemaList";

// A schema's versions, each with where it stands and what can be done with it. The schema's page has
// already read them, with what the server offers on each, so the grid lists them as they are. Two of them can be
// picked to be compared, the older the one the newer is compared with.
function SchemaVersionList({ schema, reload, comparisonDefaults }: {
  schema: JcrNode;
  reload: () => void;
  // The rules choosing what a version is compared with by default
  comparisonDefaults: string[];
}) {
  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 200,
    cardSlot: "omit",
    renderCell: params => (
      // Kept from the row: a click on an action, or inside its dialog, is not a click on the version
      <Box onClick={event => event.stopPropagation()}>
        <SchemaVersionActions version={params.row} schema={schema} reload={reload}
          comparisonDefaults={comparisonDefaults} />
      </Box>
    ),
  } ], [ schema, reload, comparisonDefaults ]);

  const versions = useMemo(() => versionsOf(schema), [ schema ]);
  const navigate = useNavigate();
  const [ picking, setPicking ] = useState(false);
  const [ picked, setPicked ] = useState<string[]>([]);
  // Of those picked, the versions the schema still has
  const ticked = useMemo(() => versions.filter(version => picked.includes(nameOf(version))), [ versions, picked ]);
  const pickColumn = useMemo<EntityGridColumn[]>(() => (picking ? [ {
    field: "__compare__",
    headerName: "Compare",
    width: 100,
    // Before the version's card on a phone, as its own card has no place for it
    renderCell: params => {
      const name = nameOf(params.row);
      const checked = picked.includes(name);
      return (
        <Box onClick={event => event.stopPropagation()}>
          <Checkbox size="small" checked={checked} disabled={!checked && ticked.length === 2}
            slotProps={{ input: { "aria-label": `Compare version ${labelOf(params.row)}` } }}
            onChange={event => setPicked(current => (event.target.checked
              ? [ ...current, name ] : current.filter(other => other !== name)))} />
        </Box>
      );
    },
  } ] : []), [ picking, picked, ticked ]);
  const stop = () => {
    setPicking(false);
    setPicked([]);
  };
  const compare = () => {
    const [ older, newer ] = [ ...ticked ].sort((first, second) => createdOf(first) - createdOf(second));
    void navigate(comparisonPageUrl(nameOf(schema), nameOf(older), nameOf(newer)));
  };

  return (
    <Stack spacing={1}>
      { versions.length > 1 && (
        <Stack direction="row" sx={{ alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          { picking ? (
            <>
              <Typography>
                { ticked.length === 2 ? "Compare these two versions?" : "Tick the two versions to compare." }
              </Typography>
              <Button variant="contained" disabled={ticked.length !== 2} onClick={compare}>Compare</Button>
              <Button onClick={stop}>Cancel</Button>
            </>
          ) : (
            <Button startIcon={<CompareArrowsIcon />} onClick={() => setPicking(true)}>Compare versions</Button>
          ) }
        </Stack>
      ) }
      <EntityDataGrid
        entityType={SCHEMA_VERSION_TYPE}
        rows={versions}
        leadingColumns={pickColumn}
        extraColumns={actionsColumn}
        pageSize={10}
        pageSizeOptions={[ 10, 25, 50 ]}
        emptyMessage="This schema has no versions."
        noResultsMessage="No version matches"
        searchLabel="Search versions"
      />
    </Stack>
  );
}

export default SchemaVersionList;
