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

import { lazy, type ReactNode, Suspense } from "react";

import { CircularProgress, Typography } from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { useNode } from "@iap/frontend-commons/useNode";
import TagChip from "@iap/tags/TagChip";

import { CATALOGUE_SELECTORS, readCatalogue } from "../catalogueApi";
import { cataloguePageUrl, descriptionOf, labelOf, nameOf, pathOf, titleOf, versionsOf } from "./catalogueModel";
import CatalogueVersionActions from "./CatalogueVersionActions";

// Loaded only when a version is shown, as on a submission's form
const DataCatalogue = lazy(() => import("../catalogue/DataCatalogue"));

// Nothing is chosen on a version's page, and nothing can be
const NOTHING_CHOSEN: readonly string[] = [];

const ignoreChange = () => undefined;

interface CatalogueVersionViewProps {
  catalogue: SerializedNode;
  versionName: string;
  // What the catalogue's own page would say about it, such as that it is retired
  pageNotices: ReactNode;
  reloadCatalogue: () => void;
}

// One version of a catalogue: where it stands, what can be done with it, and every field it offers, to browse
// but not to choose. The catalogue it belongs to heads the title and leads back from the breadcrumb trail.
function CatalogueVersionView({ catalogue, versionName, pageNotices, reloadCatalogue }: CatalogueVersionViewProps) {
  usePageCrumbs([ { path: cataloguePageUrl(nameOf(catalogue)), label: titleOf(catalogue) } ]);
  const version = versionsOf(catalogue).find(candidate => nameOf(candidate) === versionName);
  const { value: content, loadError } =
    useNode(`${pathOf(catalogue)}/${versionName}`, CATALOGUE_SELECTORS, readCatalogue);

  if (!version) {
    return (
      <AdminScreen title={`Version ${versionName}`} titlePrefix={titleOf(catalogue)}>
        <Typography>{`This catalogue has no version ${versionName}.`}</Typography>
      </AdminScreen>
    );
  }

  return (
    <AdminScreen
      title={`Version ${labelOf(version)}`}
      titlePrefix={titleOf(catalogue)}
      status={<TagChip tags={version.tags} />}
      description={descriptionOf(version)}
      action={
        <ActionsMenu label={`Actions for version ${labelOf(version)}`}>
          <CatalogueVersionActions place="versionPage" version={version} catalogue={catalogue}
            reload={reloadCatalogue} />
        </ActionsMenu>
      }
      disablePanel
    >
      {pageNotices}
      <Suspense fallback={<CircularProgress aria-label="Loading the catalogue" />}>
        <DataCatalogue catalogue={content} error={loadError} value={NOTHING_CHOSEN} onChange={ignoreChange}
          readOnly />
      </Suspense>
    </AdminScreen>
  );
}

export default CatalogueVersionView;
