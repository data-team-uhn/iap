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

import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from "react";

import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from "@mui/material";

import { ReLoginContext } from "@iap/frontend-commons/reLogin";

import LoginForm from "./LoginForm";

// Asks the user to sign in again without leaving the page, so that unsaved work survives an
// expired session. Short of signing in, Cancel is the only way to close it, and it calls
// `onAbandoned`.
//
// The dialog has no `onClose`. MUI closes a dialog on Escape or a backdrop click only through
// `onClose`, so neither closes this one. An `onClose` that only closed the dialog would leave the
// requests waiting for the sign-in unanswered.
export function ReLoginDialog(
  { open, onSignedIn, onAbandoned }: { open: boolean; onSignedIn: () => void; onAbandoned: () => void },
) {
  return (
    <Dialog open={open} maxWidth="xs" fullWidth>
      <DialogTitle>Your session has expired</DialogTitle>
      <DialogContent>
        <Typography variant="description" sx={{ pb: 2 }}>
          Sign in again to continue. Nothing you have entered will be lost.
        </Typography>
        <LoginForm onSuccess={onSignedIn} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onAbandoned}>Cancel</Button>
      </DialogActions>
    </Dialog>
  );
}

type Waiting = RefObject<((recovered: boolean) => void)[]>;

// Hands every queued request the same answer. The queue is taken before any of them is released,
// since a retry that expires again will queue itself afresh.
const release = (waiting: Waiting, recovered: boolean) => {
  const pending = waiting.current;
  waiting.current = [];
  pending.forEach(resolve => { resolve(recovered); });
};

// Lets `useAuthenticatedFetch` sign the user in again after their session expires. It opens
// ReLoginDialog, and answers every request waiting for the sign-in once the user signs in or
// gives up. Mount it around any part of the application that sends requests to the server.
//
// It asks for a local username and password. An account synced from an identity provider has
// no local password, so its user can only cancel.
export function ReLoginProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  // A ref, not state: several requests can fail in the same tick, and state set by one would not
  // yet be visible to the next.
  const waiting: Waiting = useRef<((recovered: boolean) => void)[]>([]);

  const requestReLogin = useCallback(() => new Promise<boolean>(resolve => {
    waiting.current.push(resolve);
    setOpen(true);
  }), []);

  const settle = useCallback((recovered: boolean) => {
    setOpen(false);
    release(waiting, recovered);
  }, []);

  // Once the provider is gone nothing can sign in, so the requests still waiting fail
  useEffect(() => () => { release(waiting, false); }, []);

  return (
    <ReLoginContext value={requestReLogin}>
      {children}
      <ReLoginDialog open={open} onSignedIn={() => { settle(true); }} onAbandoned={() => { settle(false); }} />
    </ReLoginContext>
  );
}
