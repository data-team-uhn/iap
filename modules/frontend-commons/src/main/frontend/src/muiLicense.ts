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

import { LicenseInfo } from "@mui/x-license";

// Registers the MUI X commercial license, unlocking the Pro components (DataGridPro, ...).
// Import this module (for its side effect) from any file that renders a Pro component, so the
// key is always set before the first render, no matter which entry point loads first.
//
// The key is not a secret: MUI X licensing is honor-based and the key is validated client-side,
// so it necessarily ships in the public JS bundle. It is still kept in this one place so that
// rotating it (or moving it to a build-time injection) is a single-file change.
//
// TODO: replace the placeholder below with the team's actual license key.
LicenseInfo.setLicenseKey("REPLACE-WITH-THE-TEAM-MUI-X-LICENSE-KEY");
