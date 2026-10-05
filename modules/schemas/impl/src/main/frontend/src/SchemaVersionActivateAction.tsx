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

import { labelOf, offers, pathOf } from "./schemaModel";

import type { SchemaVersionActionProps } from "./SchemaVersionActions";

// Opens a version to new submissions: a draft for the first time, once it passes the publish checks,
// or a retired version again.
function SchemaVersionActivateAction(props: SchemaVersionActionProps) {
  const { version } = props;
  if (!offers(version, "activate")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(version)}
      reload={props.reload}
      report={props.report}
      icon={<PublishOutlinedIcon fontSize="small" />}
      label="Activate"
      event="activate"
      title={`Activate version ${labelOf(version)}`}
      explanation={"New submissions will be possible against this version. A draft is checked first, and "
        + "from then on only its wording can change. Activating it does not retire other versions."}
      done={`Version ${labelOf(version)} is active`}
    />
  );
}

export default SchemaVersionActivateAction;
