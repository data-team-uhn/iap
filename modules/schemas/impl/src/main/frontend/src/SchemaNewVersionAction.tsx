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

import { useState } from "react";

import LibraryAddOutlinedIcon from "@mui/icons-material/LibraryAddOutlined";
import { useNavigate } from "react-router";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import NewSchemaVersionDialog from "./NewSchemaVersionDialog";
import { nameOf, pathOf } from "./schemaModel";
import { versionPageUrl } from "./useSchemaList";

import type { SchemaActionProps } from "./SchemaActions";

// Adds a version to a schema, empty or as a copy of one of its existing versions, and opens it.
function SchemaNewVersionAction({ schema, reload }: SchemaActionProps) {
  const [ creating, setCreating ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  const navigate = useNavigate();
  if (!offers(schema, "createVersion")) {
    return null;
  }
  return (
    <>
      <ActionIcon label="New version" icon={<LibraryAddOutlinedIcon fontSize="small" />}
        onClick={() => setCreating(true)} />
      { creating && (
        <NewSchemaVersionDialog
          schema={schema}
          onCreate={async (label, source) => {
            const created = await sendEvent(doFetch, pathOf(schema), "createVersion",
              { version: label, ...source && { source } });
            setCreating(false);
            notify({ title: `Version ${label} is created`, severity: "success" });
            await reload();
            if (created) {
              void navigate(versionPageUrl(nameOf(schema), created.slice(created.lastIndexOf("/") + 1)));
            }
          }}
          onClose={() => setCreating(false)}
        />
      ) }
    </>
  );
}

export default SchemaNewVersionAction;
