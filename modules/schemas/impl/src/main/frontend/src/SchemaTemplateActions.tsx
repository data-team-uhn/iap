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

import { useContext, useState } from "react";

import DeleteSweepOutlinedIcon from "@mui/icons-material/DeleteSweepOutlined";
import UploadFileOutlinedIcon from "@mui/icons-material/UploadFileOutlined";

import { ActionIcon, EventAction } from "@iap/frontend-commons/components/EventAction";
import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { offers, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { pathOf } from "./schemaModel";
import { ReloadTree } from "./schemaTree";

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

// Gives a document requirement of a draft its template, or takes it away, when the server offers it. For a generated
// document the template is what the platform fills in; otherwise it is a blank the submitter starts from.
function SchemaTemplateActions({ node }: { node: SerializedNode }) {
  const [ uploading, setUploading ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  const reload = useContext(ReloadTree);
  const notify = useNotice();
  const generated = node.generated === true;
  const hasTemplate = typeof node.template === "object";

  const upload = (file: File) => {
    const body = new FormData();
    body.append("file", file);
    setUploading(true);
    sendEvent(doFetch, pathOf(node), "setTemplate", body)
      .then(async () => {
        notify({ title: "Template uploaded", severity: "success" });
        await reload();
      })
      .catch((error: unknown) => notify({ title: "The template was not uploaded", message: messageOf(error) }))
      .finally(() => setUploading(false));
  };

  // An input made on the spot rather than drawn: the action may be in a menu, which is gone by the time a file is
  // picked, and an input inside it would be gone with it
  const pick = () => {
    const input = document.createElement("input");
    input.type = "file";
    if (generated) {
      // Only a hint for the file dialog. The server checks the type
      input.accept = `.docx,${DOCX}`;
    }
    input.addEventListener("change", () => {
      const file = input.files?.[0];
      if (file) {
        upload(file);
      }
    });
    input.click();
  };

  return (
    <>
      { offers(node, "setTemplate") && (
        <ActionIcon label={hasTemplate ? "Replace template" : "Upload template"}
          icon={<UploadFileOutlinedIcon fontSize="small" />} onClick={pick} disabled={uploading}
          loading={uploading} />
      ) }
      { hasTemplate && offers(node, "removeTemplate") && (
        <EventAction
          path={pathOf(node)}
          reload={reload}
          icon={<DeleteSweepOutlinedIcon fontSize="small" />}
          label="Remove template"
          workingLabel="Removing…"
          event="removeTemplate"
          // The removal replaces the template with nothing
          params={{ content: "null" }}
          color="error"
          title="Remove the template"
          explanation={generated
            ? "Nothing can be generated for this document without a template, and the version cannot be published."
            : "Submitters are no longer offered a blank to start from."}
        />
      ) }
    </>
  );
}

export default SchemaTemplateActions;
