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

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import { Alert, AlertTitle, Button, Grow, IconButton, Stack, type AlertColor } from "@mui/material";

// The outcome of an action that acted at once, reported over the page. It suits an outcome the
// screen does not already show.
export interface Notice {
  // What happened, in the fewest words that identify it: which thing, and what happened to it or
  // did not.
  title: string;
  // More to say, such as why; nothing under the title if undefined.
  message?: string;
  // An error by default.
  severity?: AlertColor;
  // Tries again, from a Retry button on the notice; no button if undefined.
  onRetry?: () => void;
}

// How many notices the page shows at once; past that, the oldest gives way.
const MAX_SHOWN = 5;

// How long a success or info notice stays, in milliseconds.
const FADE_AFTER = 4000;

interface ShownNotice extends Notice {
  // Tells two notices apart once they are on screen, whatever they say
  id: number;
}

interface NoticeAlertProps {
  notice: ShownNotice;
  dismiss: (id: number) => void;
}

// One notice, over the screen it was raised on. An error or a warning stays until it is dismissed
// or retried, since it may carry a remedy. A success or info notice goes after FADE_AFTER.
function NoticeAlert({ notice, dismiss }: NoticeAlertProps) {
  const severity = notice.severity ?? "error";
  const transient = severity === "success" || severity === "info";
  const { id } = notice;

  useEffect(() => {
    if (!transient) {
      return undefined;
    }
    const timer = setTimeout(() => dismiss(id), FADE_AFTER);
    return () => clearTimeout(timer);
  }, [ transient, dismiss, id ]);

  return (
    <Grow in>
      <Alert
        severity={severity}
        // Both controls go in `action`: an Alert given an `action` drops its own close button
        action={
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
            { notice.onRetry
              && (
                <Button
                  color="inherit"
                  size="small"
                  onClick={() => {
                    // Dismissed first, so a retry that fails shows its own notice
                    dismiss(id);
                    notice.onRetry?.();
                  }}
                >
                  Retry
                </Button>
              )}
            <IconButton color="inherit" size="small" aria-label="Dismiss" onClick={() => dismiss(id)}>
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
        }
      >
        <AlertTitle>{notice.title}</AlertTitle>
        {notice.message}
      </Alert>
    </Grow>
  );
}

const NoticeContext = createContext<((notice: Notice) => void) | undefined>(undefined);

// Shows the notices raised beneath it, stacked in a bottom corner of the page, the newest lowest.
// Mount it once, at the top of the page.
//
// Each notice keeps its own lifetime. A notice with the same title and message as one already shown
// replaces it, and becomes the newest.
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [ notices, setNotices ] = useState<ShownNotice[]>([]);
  const nextId = useRef(0);

  const raise = useCallback((notice: Notice) => {
    const id = nextId.current++;
    setNotices(shown => [
      ...shown.filter(other => other.title !== notice.title || other.message !== notice.message),
      { ...notice, id },
    ].slice(-MAX_SHOWN));
  }, []);

  const dismiss = useCallback((id: number) => {
    setNotices(shown => shown.filter(notice => notice.id !== id));
  }, []);

  return (
    <NoticeContext.Provider value={raise}>
      {children}
      { notices.length > 0 && (
        <Stack
          spacing={1}
          sx={{
            position: "fixed",
            insetBlockEnd: { xs: 8, sm: 24 },
            insetInlineStart: { xs: 8, sm: 24 },
            insetInlineEnd: { xs: 8, sm: "auto" },
            zIndex: "snackbar",
          }}
        >
          { notices.map(notice => <NoticeAlert key={notice.id} notice={notice} dismiss={dismiss} />) }
        </Stack>
      ) }
    </NoticeContext.Provider>
  );
}

// Raises a notice on the page. Throws without a NoticeProvider above.
export function useNotice(): (notice: Notice) => void {
  const raise = useContext(NoticeContext);
  if (!raise) {
    throw new Error("useNotice() needs a <NoticeProvider> above it");
  }
  return raise;
}
