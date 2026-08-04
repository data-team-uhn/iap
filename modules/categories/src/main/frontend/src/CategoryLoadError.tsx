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

import { Alert, AlertTitle, Button } from "@mui/material";

import type { SxProps, Theme } from "@mui/material";

interface CategoryLoadErrorProps {
  // The failure, as reported by the server or by the network layer.
  message: string;
  // Fetches the category tree again. The attempt is tracked here, so the button can show its
  // progress; the fetch reports its own outcome by updating (or clearing) the message.
  onRetry: () => Promise<void>;
  sx?: SxProps<Theme>;
}

// How both category screens report a tree they could not load: in place, with the only useful
// remedy attached. A load failure is a state of the screen rather than an event to acknowledge, so
// a modal would be a dead end - and reporting in place leaves whatever was last fetched
// successfully visible underneath, stale but still readable. Both screens are administrative, so
// both show the underlying failure rather than hiding it behind a generic sentence.
function CategoryLoadError({ message, onRetry, sx }: CategoryLoadErrorProps) {
  const [ retrying, setRetrying ] = useState(false);

  const retry = () => {
    setRetrying(true);
    void onRetry().finally(() => setRetrying(false));
  };

  return (
    <Alert
      severity="error"
      sx={sx}
      action={
        <Button color="inherit" size="small" loading={retrying} onClick={retry}>
          Retry
        </Button>
      }
    >
      <AlertTitle>The categories could not be loaded</AlertTitle>
      {message}
    </Alert>
  );
}

export default CategoryLoadError;
