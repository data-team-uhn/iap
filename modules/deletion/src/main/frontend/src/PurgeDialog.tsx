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

import { Alert, Typography } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";

import type { ArchiveEntry } from "./archiveApi";

/**
 * Asks before purging an archive entry, and says how much will be destroyed. `onPurge` sends the purge;
 * the dialog closes once it resolves.
 */
export function PurgeDialog({ entry, onPurge, onClose }: {
  entry: ArchiveEntry;
  onPurge: () => Promise<void>;
  onClose: () => void;
}) {
  return (
    <ConfirmActionDialog
      title="Purge this entry?"
      confirmLabel="Purge"
      workingLabel="Purging…"
      confirmColor="error"
      onConfirm={onPurge}
      onClose={onClose}
    >
      <Typography gutterBottom>
        {`Everything archived under ${entry.requestedPath} will be destroyed. This cannot be undone.`}
      </Typography>
      <Alert severity="warning">
        {`${String(entry.itemCount)} archived item(s) will be permanently removed.`}
      </Alert>
    </ConfirmActionDialog>
  );
}
