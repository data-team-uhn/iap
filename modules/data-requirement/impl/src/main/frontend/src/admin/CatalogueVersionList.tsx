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

import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

import { labelOf, versionsOf } from "./catalogueModel";
import CatalogueVersionActions from "./CatalogueVersionActions";
import { CATALOGUE_VERSION_TYPE } from "./catalogueVersionGrid";

// A catalogue's versions, with where each stands and what can be done with it. The versions come with the
// catalogue, so the grid lists them without fetching.
function CatalogueVersionList({ catalogue, reload }: { catalogue: SerializedNode; reload: () => void }) {
  const actionsColumn = useMemo<EntityGridColumn[]>(() => [ {
    field: "__actions__",
    headerName: "Actions",
    width: 140,
    // On a phone, one menu at the end of a version's card
    cardSlot: "actions",
    renderCell: params => (
      <ActionsMenu label={`Actions for version ${labelOf(params.row)}`}>
        <CatalogueVersionActions version={params.row} catalogue={catalogue} reload={reload} place="versionList" />
      </ActionsMenu>
    ),
  } ], [ catalogue, reload ]);
  const versions = useMemo(() => versionsOf(catalogue), [ catalogue ]);

  return (
    <EntityDataGrid
      entityType={CATALOGUE_VERSION_TYPE}
      rows={versions}
      extraColumns={actionsColumn}
      pageSize={10}
      pageSizeOptions={[ 10, 25, 50 ]}
      emptyMessage="This catalogue has no versions."
      noResultsMessage="No version matches"
      searchLabel="Search versions"
    />
  );
}

export default CatalogueVersionList;
