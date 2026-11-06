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

// A full-width notice, such as a maintenance announcement, drawn from a data-only extension. It suits
// the `iap/coreUI/frameTop` point, where `ext:visibleBeforeLogin` shows it before sign-in too. The
// extension gives:
// - `ext:data`: the message, in markdown, such as one with a link to a status page. Without it, the
//   banner renders nothing.
// - `ext:severity`: `error`, `warning`, `info` or `success`; `info` for anything else.
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
