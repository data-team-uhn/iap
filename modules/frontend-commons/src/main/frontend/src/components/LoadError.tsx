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
  // What could not be loaded, such as "The categories could not be loaded".
  title: string;
  // Why, when the cause is known; nothing is shown under the title otherwise.
  message?: string;
  // Fetches again, while the button shows its progress. The fetch reports its own outcome, by
  // changing what this shows or by removing it.
  onRetry: () => Promise<void>;
  sx?: SxProps<Theme>;
}

// Reports that a screen could not load something, with a Retry button.
//
// Sample usage:
// { loadError && <LoadError title="The widgets could not be loaded" message={loadError} onRetry={load} /> }
//
function LoadError({ title, message, onRetry, sx }: LoadErrorProps) {
  // A retry that fails changes `message`, so nothing is reported here
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
