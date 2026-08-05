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

import { Alert, AlertTitle, Button } from "@mui/material";

import { useAsyncAction } from "../useAsyncAction";

import type { SxProps, Theme } from "@mui/material";

interface LoadErrorProps {
  // What could not be loaded, e.g. "The categories could not be loaded".
  title: string;
  // Why, when the cause is known - which, on an administrative screen, is worth showing rather than
  // hiding behind a generic sentence.
  message?: string;
  // Fetches again. The attempt is tracked here so the button can show its progress; the fetch
  // reports its own outcome by updating, or clearing, what is displayed.
  onRetry: () => Promise<void>;
  sx?: SxProps<Theme>;
}

// How a screen reports something it could not load: in place of what it failed to fill, with the
// only useful remedy attached. A load failure is a state of the screen rather than an event to
// acknowledge, so a modal would be a dead end - and reporting in place leaves whatever was last
// fetched successfully visible underneath, stale but still readable.
//
// Sample usage:
// { loadError && <LoadError title="The widgets could not be loaded" message={loadError} onRetry={load} /> }
//
function LoadError({ title, message, onRetry, sx }: LoadErrorProps) {
  // Nothing to report from here: a retry that fails updates the very message being displayed
  const { working: retrying, run } = useAsyncAction<never>({ onFailure: () => undefined });

  return (
    <Alert
      severity="error"
      sx={sx}
      action={
        <Button color="inherit" size="small" loading={retrying} onClick={() => run(onRetry)}>
          Retry
        </Button>
      }
    >
      <AlertTitle>{title}</AlertTitle>
      {message}
    </Alert>
  );
}

export default LoadError;
