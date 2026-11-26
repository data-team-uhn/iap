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

import { Button, DialogActions, DialogContent, Typography } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import type { Letter } from "./reviewData";

// A letter already sent, to read again, as when the study team answers it
export function SentLetter({ letter, onClose }: { letter: Letter; onClose: () => void }) {
  const sent = new Date(letter.at).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
  return (
    <ResponsiveDialog open onClose={onClose} title="Your letter" width="md" withCloseButton>
      <DialogContent>
        <Typography variant="description" sx={{ display: "block", mb: 2 }}>
          {letter.outcome === "approved" ? "Sent with your approval" : "Sent with your request for changes"} on{" "}
          {sent}.
        </Typography>
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{letter.text}</Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void navigator.clipboard.writeText(letter.text)}>Copy</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}
