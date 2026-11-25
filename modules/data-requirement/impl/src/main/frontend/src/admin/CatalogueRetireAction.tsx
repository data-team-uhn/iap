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

import { pathOf, titleOf } from "./catalogueModel";

import type { CatalogueActionProps } from "./CatalogueActions";

// Closes a catalogue as a whole, which retires every version with it.
function CatalogueRetireAction({ catalogue, reload }: CatalogueActionProps) {
  if (!offers(catalogue, "retire")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(catalogue)}
      reload={reload}
      icon={<ArchiveOutlinedIcon fontSize="small" />}
      label="Retire"
      event="retire"
      color="warning"
      title={`Retire ${titleOf(catalogue)}`}
      explanation={"New schema versions will not be able to offer any of its versions until it is reopened. "
        + "Schema versions that already offer one keep it."}
      done={`${titleOf(catalogue)} is retired`}
    />
  );
}

export default CatalogueRetireAction;
