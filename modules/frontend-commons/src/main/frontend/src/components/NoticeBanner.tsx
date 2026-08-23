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

import { Alert } from "@mui/material";

import { type Extension } from "@iap/ui-extension/ExtensionList";

import FormattedText from "./FormattedText";

const SEVERITIES = ["error", "warning", "info", "success"] as const;

// A full-width notice banner (e.g. a maintenance announcement), rendered from a data-only
// extension — typically registered on `iap/coreUI/frameTop`, with `ext:visibleBeforeLogin`
// when the notice must also reach users before they sign in. The extension node provides:
// - `ext:data`: the message (markdown, so it can carry e.g. a link to a status page);
// - `ext:severity` (optional): one of error/warning/info/success, defaulting to info.
export default function NoticeBanner({ extension }: { extension: Extension }) {
  const message = extension["ext:data"] as string | undefined;
  const severity = SEVERITIES.find(candidate => candidate === extension["ext:severity"]) ?? "info";

  if (!message) {
    return null;
  }
  return (
    <Alert severity={severity} sx={{ borderRadius: 0 }}>
      <FormattedText variant="body2" sx={{ "& p": { margin: 0 } }}>
        {message}
      </FormattedText>
    </Alert>
  );
}
