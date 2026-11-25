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

import AdminScreen from "@iap/admin-console/AdminScreen";
import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

import CatalogueActions from "./CatalogueActions";
import { CATALOGUE_OF, CATALOGUE_TYPE } from "./catalogueGrid";
import { labelOf, titleOf } from "./catalogueModel";
import CatalogueVersionActions from "./CatalogueVersionActions";

// The "Catalogues" administrative tool: every catalogue of data a data requirement can offer, searchable and
// sortable, with its versions nested under it and the actions offered on each.
function CatalogueManager() {
  const [ refreshToken, setRefreshToken ] = useState(0);

  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 140,
    // On a phone, one menu at the end of a catalogue's card
    cardSlot: "actions",
    renderCell: params => {
      const row = params.row;
      const parent = row[CATALOGUE_OF] as SerializedNode | undefined;
      const catalogue = parent ?? row;
      const reload = () => setRefreshToken(token => token + 1);
      return parent
        ? (
          <ActionsMenu label={`Actions for version ${labelOf(row)}`}>
            <CatalogueVersionActions place="versionList" version={row} catalogue={catalogue} reload={reload} />
          </ActionsMenu>
        )
        : (
          <ActionsMenu label={`Actions for “${titleOf(catalogue)}”`}>
            <CatalogueActions catalogue={catalogue} reload={reload} />
          </ActionsMenu>
        );
    },
  } ], []);

  return (
    <AdminScreen
      title="Catalogues"
      description={"The data a schema can ask a submitter to choose from. Each catalogue is versioned, and a "
        + "schema version names the catalogue version it offers."}
    >
      <EntityDataGrid
        entityType={CATALOGUE_TYPE}
        extraColumns={actionsColumn}
        refreshToken={refreshToken}
        pageSize={25}
        pageSizeOptions={[ 10, 25, 50 ]}
        height={640}
        emptyMessage="No catalogues are installed."
        noResultsMessage="No catalogue matches"
        searchLabel="Search catalogues"
      />
    </AdminScreen>
  );
}

export default CatalogueManager;
