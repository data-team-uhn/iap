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

import ArchiveOutlinedIcon from "@mui/icons-material/ArchiveOutlined";

import { EventAction } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { labelOf, pathOf } from "./catalogueModel";

import type { CatalogueVersionActionProps } from "./CatalogueVersionActions";

// Stops offering an active version to new schema versions. Those that already offer it keep it.
function CatalogueVersionRetireAction({ version, reload }: CatalogueVersionActionProps) {
  if (!offers(version, "retire")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(version)}
      reload={reload}
      icon={<ArchiveOutlinedIcon fontSize="small" />}
      label="Retire"
      event="retire"
      color="warning"
      title={`Retire version ${labelOf(version)}`}
      explanation={"New schema versions will no longer be able to offer it. Schema versions that already offer "
        + "it keep it."}
      done={`Version ${labelOf(version)} is retired`}
    />
  );
}

export default CatalogueVersionRetireAction;
