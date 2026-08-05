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

import CloseIcon from "@mui/icons-material/Close";
import { Alert, AlertTitle, Button, IconButton, Snackbar, Stack, type AlertColor } from "@mui/material";

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

interface NoticeSnackbarProps {
  // The notice to show; nothing is shown while this is undefined.
  notice?: Notice;
  onClose: () => void;
}

// How an immediate action reports an outcome it has nowhere else to put: briefly, over the screen
// it happened on, without taking it over. A modal would be the wrong weight - a failed action
// usually means nothing changed, and interrupting to say so is a poor trade - while a report at the
// top of a long screen can land out of sight of the row that caused it.
//
// A failure or a warning stays until it is dismissed, retried or replaced: it carries something to
// read and, often, something to click, so taking it away on a timer would be taking away the
// remedy. Only the cheerful ones are allowed to fade.
//
// Sample usage:
// const [ notice, setNotice ] = useState<Notice>();
// ...
// <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
//
function NoticeSnackbar({ notice, onClose }: NoticeSnackbarProps) {
  const severity = notice?.severity ?? "error";
  const transient = severity === "success" || severity === "info";

  return (
    <Snackbar
      open={!!notice}
      autoHideDuration={transient ? 4000 : null}
      onClose={(_event, reason) => {
        // A stray click elsewhere is not a dismissal; it would too easily take the remedy with it
        if (reason !== "clickaway") {
          onClose();
        }
      }}
    >
      <Alert
        severity={severity}
        // Both controls have to be given here: an Alert's own close button gives way to whatever
        // `action` it is handed
        action={
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
            { notice?.onRetry
              && (
                <Button
                  color="inherit"
                  size="small"
                  onClick={() => {
                    // Out of the way first: a second failure raises its own notice
                    onClose();
                    notice.onRetry?.();
                  }}
                >
                  Retry
                </Button>
              )}
            <IconButton color="inherit" size="small" aria-label="Dismiss" onClick={onClose}>
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
        }
      >
        <AlertTitle>{notice?.title}</AlertTitle>
        {notice?.message}
      </Alert>
    </Snackbar>
  );
}

export default NoticeSnackbar;
