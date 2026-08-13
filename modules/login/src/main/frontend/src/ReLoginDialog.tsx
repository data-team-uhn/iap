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

// Signing back in without leaving the page, so that work in progress is not lost to an expired
// session. Only dismissable by giving up: the requests that ran into the expired session are
// waiting on this, and abandoning it fails them, which is better than a dialog with no way out
// when the user cannot produce the credentials it is asking for.
//
// The absence of `onClose` is what enforces that, and it is deliberate rather than an oversight:
// MUI swallows Escape and reports a backdrop click by calling `onClose(event, reason)`, so a dialog
// that does not supply one cannot be dismissed by either. Adding an `onClose` that merely closes
// would strand every request waiting on `settle`, so if this ever needs one it has to answer them.
// (There is no `disableEscapeKeyDown` to reach for here -- MUI dropped that prop in v9 in favour of
// the `reason` argument.)
export function ReLoginDialog(
  { open, onSignedIn, onAbandoned }: { open: boolean; onSignedIn: () => void; onAbandoned: () => void },
) {
  return (
    <Dialog open={open} maxWidth="xs" fullWidth>
      <DialogTitle>Your session has expired</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ pb: 2 }}>
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

// Recovers an expired session by asking for credentials, and releases the requests that were waiting
// on it. Mount it around any part of the application that talks to the repository.
//
// This is the local-credentials implementation of the contract in
// @iap/frontend-commons/reLogin, which is all a deployment signing its users in locally needs.
// One authenticating against an external identity provider will want a provider of its own: the
// sign-in has to happen in a popup window, since navigating this page to the provider and back is
// what would lose the unsaved work being protected here. Only this component changes for that --
// `useAuthenticatedFetch` and every caller of it stay as they are.
export function ReLoginProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  // Held in a ref rather than in state: several requests can fail in the same tick, and each has
  // to be remembered even though only the last render's state would be visible to them.
  const waiting: Waiting = useRef<((recovered: boolean) => void)[]>([]);

  const requestReLogin = useCallback(() => new Promise<boolean>(resolve => {
    waiting.current.push(resolve);
    setOpen(true);
  }), []);

  const settle = useCallback((recovered: boolean) => {
    setOpen(false);
    release(waiting, recovered);
  }, []);

  // Nothing can sign in once this is gone, so anything still waiting has to fail rather than hang
  useEffect(() => () => { release(waiting, false); }, []);

  return (
    <ReLoginContext value={requestReLogin}>
      {children}
      <ReLoginDialog open={open} onSignedIn={() => { settle(true); }} onAbandoned={() => { settle(false); }} />
    </ReLoginContext>
  );
}
