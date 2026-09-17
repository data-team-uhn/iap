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

import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle,
  FormControlLabel, Switch,
} from "@mui/material";

import { useCatcherToggle } from "./useCaughtMail";

// What each direction costs, said before it is done rather than after. Both are confirmed: turning
// it on diverts every message the platform would have sent, and turning it off starts delivering to
// whatever addresses the data happens to hold, which on a test instance are usually real people's.
const WARNINGS = {
  on: "Nothing will be emailed while this is on. Everything the platform would have sent — password"
    + " resets, invitations, notifications — is kept here instead, and the people it was addressed"
    + " to will not receive it.",
  off: "Mail will be delivered from now on, to whatever addresses this instance holds. On a test or"
    + " demo instance those may belong to real people.",
};

/**
 * The switch that turns mail catching on and off, and the confirmation in front of it.
 *
 * @param enabled whether mail is being caught right now
 * @param onChanged called once the setting has been written, so the caller can read the state back
 */
export default function CatcherSwitch(
  { enabled, onChanged }: { enabled: boolean; onChanged: () => void },
) {
  const { setCatching, failure } = useCatcherToggle();
  const [ asking, setAsking ] = useState(false);
  const [ busy, setBusy ] = useState(false);
  const wanted = !enabled;

  const confirm = async () => {
    setBusy(true);
    try {
      await setCatching(wanted);
      onChanged();
      setAsking(false);
    } catch {
      // Left open, carrying the failure. Closing here would look exactly like it had worked, on the
      // one control in the product whose two states are "mail goes out" and "mail does not".
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <FormControlLabel
        control={<Switch checked={enabled} onChange={() => setAsking(true)} />}
        label="Catch mail instead of sending it"
        slotProps={{ typography: { variant: "body2" } }}
      />
      <Dialog open={asking} onClose={() => setAsking(false)}>
        <DialogTitle>
          {wanted ? "Catch mail instead of sending it?" : "Start sending mail again?"}
        </DialogTitle>
        <DialogContent>
          <DialogContentText>{wanted ? WARNINGS.on : WARNINGS.off}</DialogContentText>
          {failure !== null && <Alert severity="error" sx={{ mt: 2 }}>{failure}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAsking(false)}>Cancel</Button>
          <Button
            onClick={() => void confirm()}
            disabled={busy}
            color={wanted ? "warning" : "primary"}
          >
            {wanted ? "Catch mail" : "Send mail"}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
