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

import { useCallback } from "react";

import { Alert } from "@mui/material";
import { useLocation } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useNode } from "@iap/frontend-commons/useNode";
import TagChip from "@iap/tags/TagChip";

import CatalogueActions from "./CatalogueActions";
import { catalogueNameFromRoute, CATALOGUES_ROOT, descriptionOf, listing, tagsOf, titleOf } from "./catalogueModel";
import CatalogueVersionList from "./CatalogueVersionList";

// The page of one catalogue: its versions and where each stands, and the lifecycle actions on them and on the
// catalogue.
function CataloguePage() {
  const { pathname } = useLocation();
  const { value: catalogue, loading, loadError, reload } =
    useNode(`${CATALOGUES_ROOT}/${catalogueNameFromRoute(pathname)}`, listing(1));
  const reloadCatalogue = useCallback(() => void reload(), [ reload ]);

  if (!catalogue) {
    return (
      <AdminScreen title="Catalogue">
        <LoadingOverlay open={loading} />
        { loadError && <LoadError title="The catalogue could not be loaded" message={loadError} onRetry={reload} /> }
      </AdminScreen>
    );
  }

  return (
    <AdminScreen
      title={titleOf(catalogue)}
      status={<TagChip tags={catalogue.tags} />}
      description={descriptionOf(catalogue)}
      action={<CatalogueActions catalogue={catalogue} reload={reload} />}
      disablePanel
    >
      { loadError && <LoadError title="The catalogue could not be reloaded" message={loadError} onRetry={reload}
        sx={{ mb: 2 }} /> }
      { tagsOf(catalogue).includes("retired") && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          This catalogue is retired. New schema versions cannot offer any of its versions.
        </Alert>
      ) }
      <CatalogueVersionList catalogue={catalogue} reload={reloadCatalogue} />
    </AdminScreen>
  );
}

export default CataloguePage;
