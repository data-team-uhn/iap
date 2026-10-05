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

// How an action that acted immediately turned out. A notice is worth raising when the outcome is
// not already visible on screen - which, for a failure, it rarely is.
export interface Notice {
  // What happened, in the fewest words that identify it: which thing, and what did not happen to it.
  title: string;
  // Why, when there is more to say than the title says.
  message?: string;
  // Defaults to an error, since that is what usually needs saying.
  severity?: AlertColor;
  // Offered as a button on the notice itself, for an outcome worth another attempt.
  onRetry?: () => void;
}

// How many notices the page shows at once; past that, the oldest gives way.
const MAX_SHOWN = 5;

// How long a cheerful notice stays, in milliseconds.
const FADE_AFTER = 4000;

interface ShownNotice extends Notice {
  // Tells two notices apart once they are on screen, whatever they say
  id: number;
}

interface NoticeAlertProps {
  notice: ShownNotice;
  dismiss: (id: number) => void;
}

// How an immediate action reports an outcome it has nowhere else to put: briefly, over the screen
// it happened on, without taking it over. A modal would be the wrong weight - a failed action
// usually means nothing changed, and interrupting to say so is a poor trade - while a report at the
// top of a long screen can land out of sight of the row that caused it.
//
// A failure or a warning stays until it is dismissed or retried: it carries something to read and,
// often, something to click, so taking it away on a timer would be taking away the remedy. Only the
// cheerful ones are allowed to fade.
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
        // Both controls have to be given here: an Alert's own close button gives way to whatever
        // `action` it is handed
        action={
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
            { notice.onRetry
              && (
                <Button
                  color="inherit"
                  size="small"
                  onClick={() => {
                    // Out of the way first: a second failure raises its own notice
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

// Shows the notices of everything beneath it, stacked in one corner of the page, so a screen raises a
// notice without keeping or drawing one of its own. Mounted once, at the top of the page.
//
// Each notice keeps its own lifetime, and the newest is nearest the edge. The same notice raised again
// is one notice, brought up to date, rather than a second copy of it.
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

// Raises a notice on the page.
export function useNotice(): (notice: Notice) => void {
  const raise = useContext(NoticeContext);
  if (!raise) {
    throw new Error("useNotice() needs a <NoticeProvider> above it");
  }
  return raise;
}
