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

import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import { DialogContentText, IconButton, Tooltip } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";

// PRETOTYPE. Removing something the reviewer wrote, an issue, a note or a reply, after asking: which one, by the start
// of its words, and that it cannot be taken back.
export default function RemoveButton({ what, text, onRemove, sx }: {
  // What it is called, such as "issue" or "reply"
  what: string;
  text: string;
  onRemove: () => Promise<void>;
  sx?: object;
}) {
  const [ asking, setAsking ] = useState(false);
  const start = text.length > 160 ? `${text.slice(0, 160).trimEnd()}…` : text;
  return (
    <>
      <Tooltip title="Remove">
        <IconButton size="small" aria-label={`Remove the ${what}`} sx={sx} onClick={() => setAsking(true)}>
          <DeleteOutlined fontSize="small" />
        </IconButton>
      </Tooltip>
      {asking && (
        <ConfirmActionDialog
          title={`Remove this ${what}?`} confirmLabel="Remove" workingLabel="Removing…" confirmColor="error"
          onConfirm={onRemove} onClose={() => setAsking(false)}
        >
          <DialogContentText sx={{ fontStyle: "italic", mb: 1 }}>“{start}”</DialogContentText>
          <DialogContentText>This cannot be undone.</DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}
