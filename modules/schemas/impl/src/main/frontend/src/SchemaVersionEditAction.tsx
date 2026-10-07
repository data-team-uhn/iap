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

import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import { useNavigate } from "react-router";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { nameOf } from "./schemaModel";
import { versionPageUrl } from "./useSchemaList";

import type { SchemaVersionActionProps } from "./SchemaVersionActions";

// Opens a version that can still change on its own page, where it is edited
function SchemaVersionEditAction({ schema, version }: SchemaVersionActionProps) {
  const navigate = useNavigate();
  if (!offers(version, "update")) {
    return null;
  }
  return (
    <ActionIcon label="Edit" icon={<EditOutlinedIcon fontSize="small" />}
      onClick={() => void navigate(versionPageUrl(nameOf(schema), nameOf(version)))} />
  );
}

export default SchemaVersionEditAction;
