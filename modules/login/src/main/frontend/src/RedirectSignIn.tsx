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

import { Button, Stack, Typography } from "@mui/material";

import { useMessage } from "@iap/frontend-commons/messages";
import { type Extension } from "@iap/ui-extension/ExtensionList";

import { loginRedirectPath } from "./loginRedirect";

// The URL this method navigates to: the extension's `iap:targetURL`, with the validated
// in-app return path attached as its `resource` parameter so the authentication endpoint
// can send the user back to where they were headed. Exported for tests.
export function redirectSignInTarget(extension: Extension): string | null {
  const target = extension["iap:targetURL"];
  if (typeof target !== "string" || !target) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(target, window.location.origin);
  } catch {
    return null;
  }
  url.searchParams.set("resource", loginRedirectPath());
  return url.toString();
}

// A sign-in method that hands authentication to an external identity provider: a single
// action navigating to the endpoint that starts the authentication round trip. The identity
// provider integration (e.g. the keycloak module) only has to register an extension
// node — this component is the whole frontend. The extension provides:
// - `iap:targetURL`: the endpoint to navigate to (required; nothing renders without it);
// - `iap:actionLabel` (optional): the button text; without one the platform's own wording is used,
//   from the message catalog rather than as an English literal;
// - `iap:hint` (optional): a short explanation displayed under the button, e.g. telling the
//   user they are about to be redirected.
export default function RedirectSignIn({ extension }: { extension: Extension }) {
  const message = useMessage();
  const target = redirectSignInTarget(extension);
  // The extension names its own button where it can; this is the wording for one that does not, and so
  // the only part of the label that is ours to translate -- the same division SignInMethods makes.
  const label = (extension["iap:actionLabel"] as string | undefined)
    ?? message("iap.login.redirectSignIn.action");
  const hint = extension["iap:hint"] as string | undefined;

  if (!target) {
    return null;
  }
  return (
    <Stack spacing={1.5}>
      <Button variant="contained" color="primary" onClick={() => window.location.assign(target)}>
        {label}
      </Button>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Stack>
  );
}
