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

import { labelOf, offers, pathOf } from "./schemaModel";

import type { SchemaVersionActionProps } from "./SchemaVersionActions";

// Closes an active version to new submissions; existing ones keep using it.
function SchemaVersionRetireAction(props: SchemaVersionActionProps) {
  const { version } = props;
  if (!offers(version, "retire")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(version)}
      reload={props.reload}
      report={props.report}
      icon={<ArchiveOutlinedIcon fontSize="small" />}
      label="Retire"
      event="retire"
      color="warning"
      title={`Retire version ${labelOf(version)}`}
      explanation="No new submissions will be possible against it. Existing submissions keep using it."
      done={`Version ${labelOf(version)} is retired`}
    />
  );
}

export default SchemaVersionRetireAction;
