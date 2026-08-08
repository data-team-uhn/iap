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

import LoadError from "@iap/frontend-commons/components/LoadError";

import type { SxProps, Theme } from "@mui/material";

interface CategoryLoadErrorProps {
  // The failure, as reported by the server or by the network layer.
  message: string;
  // Fetches the category tree again.
  onRetry: () => Promise<void>;
  sx?: SxProps<Theme>;
}

// The one sentence both category screens use for a tree they could not load, so that the manager
// and the console widget cannot drift apart on it.
function CategoryLoadError({ message, onRetry, sx }: CategoryLoadErrorProps) {
  return (
    <LoadError title="The categories could not be loaded" message={message} onRetry={onRetry} sx={sx} />
  );
}

export default CategoryLoadError;
