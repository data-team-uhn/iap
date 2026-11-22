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

import AdminScreen from "@iap/admin-console/AdminScreen";
import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";

import { CATALOGUE_TYPE } from "./catalogueGrid";

// The "Catalogues" administrative tool: every catalogue of data a data requirement can offer, searchable and
// sortable, with its versions nested under it.
function CatalogueManager() {
  return (
    <AdminScreen
      title="Catalogues"
      description={"The data a schema can ask a submitter to choose from. Each catalogue is versioned, and a "
        + "schema version names the catalogue version it offers."}
    >
      <EntityDataGrid
        entityType={CATALOGUE_TYPE}
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
