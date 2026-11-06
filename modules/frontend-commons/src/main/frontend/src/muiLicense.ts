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

// Registers the MUI X commercial license, which the Pro components such as DataGridPro need. Import
// this module from every file that renders a Pro component, so that the key is set before the first
// render, whichever entry point loads first.
//
// The key comes from the MUI_LICENSE_KEY environment variable, written into the bundle at build time
// by the DefinePlugin entry in webpack.config-template.js.
//
// The build does not fail without a key, since anyone may build this public repository. The Pro
// components then show their watermark, and a warning is logged.
const licenseKey = process.env.MUI_LICENSE_KEY;

if (licenseKey) {
  LicenseInfo.setLicenseKey(licenseKey);
} else {
  console.warn(
    "MUI_LICENSE_KEY was not set when this bundle was built, so the MUI X Pro components are "
    + "running unlicensed and will show their watermark. Set it in the build environment.");
}
