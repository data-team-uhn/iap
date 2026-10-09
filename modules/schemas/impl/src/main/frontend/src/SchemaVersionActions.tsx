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

import ActionBar from "@iap/frontend-commons/components/ActionBar";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

// A module adds an action on schema versions by shipping an `ext:Extension` on this point.
export const VERSION_ACTIONS_POINT = "SchemaVersionActions";

export interface SchemaVersionActionProps {
  version: SerializedNode;
  schema: SerializedNode;
  reload: () => void;
  removed?: () => void;
  // The rules choosing what a version is compared with by default, in order
  comparisonDefaults?: string[];
}

// Where a version's actions are shown, which their extensions can name in `places`
export type VersionActionsPlace = "versionList" | "versionPage";

function SchemaVersionActions({ place, ...props }: SchemaVersionActionProps & { place: VersionActionsPlace }) {
  return <ActionBar point={VERSION_ACTIONS_POINT} place={place} {...props} />;
}

export default SchemaVersionActions;
