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

import PublishOutlinedIcon from "@mui/icons-material/PublishOutlined";

import { EventAction } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { labelOf, pathOf, tagsOf, versionsOf } from "./catalogueModel";

import type { CatalogueVersionActionProps } from "./CatalogueVersionActions";

// Offers a version to new schema versions: a draft for the first time, once it passes its checks, or a retired
// version again. The confirmation names the active version this retires.
function CatalogueVersionActivateAction({ version, catalogue, reload }: CatalogueVersionActionProps) {
  if (!offers(version, "activate")) {
    return null;
  }
  const outgoing = versionsOf(catalogue).find(candidate => tagsOf(candidate).includes("active"));
  return (
    <EventAction
      path={pathOf(version)}
      reload={reload}
      icon={<PublishOutlinedIcon fontSize="small" />}
      label="Activate"
      event="activate"
      title={`Activate version ${labelOf(version)}`}
      explanation={"New schema versions will be able to offer this version. A draft is checked first."
        + (outgoing
          ? ` Version ${labelOf(outgoing)} is retired in the same step: schema versions that already offer it`
            + " keep it, but new ones cannot."
          : " Nothing is retired: this catalogue has no active version at the moment.")}
      done={`Version ${labelOf(version)} is active`}
    />
  );
}

export default CatalogueVersionActivateAction;
