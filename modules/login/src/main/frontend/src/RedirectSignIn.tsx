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

import { type Extension } from "@iap/ui-extension/ExtensionList";

import { loginRedirectPath } from "./loginRedirect";

// The URL this method goes to: the extension's `ext:targetURL`, with the page to return to,
// from `loginRedirectPath`, in its `resource` query parameter. `null` if the extension has no
// target, or one that is not a URL.
//
// Where the user lands after signing in is up to the endpoint, which may ignore `resource`.
// Sling's OIDC authentication handler does: it sends the user back to the path that started the
// sign-in.
export function redirectSignInTarget(extension: Extension): string | null {
  const target = extension["ext:targetURL"];
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

// A sign-in method that hands authentication to an identity provider: one button, leading to
// the endpoint that starts the sign-in. An identity provider needs no frontend code, only an
// extension naming this component. The extension's properties:
// - `ext:targetURL`: the endpoint. Without a usable one, nothing is shown.
// - `ext:actionLabel`, optional: the button's text, "Continue to sign-in" by default.
// - `ext:hint`, optional: a line under the button, such as one saying the user will be
//   redirected.
export default function RedirectSignIn({ extension }: { extension: Extension }) {
  const target = redirectSignInTarget(extension);
  const label = (extension["ext:actionLabel"] as string | undefined) ?? "Continue to sign-in";
  const hint = extension["ext:hint"] as string | undefined;

  if (!target) {
    return null;
  }
  return (
    <Stack spacing={1.5}>
      <Button variant="contained" color="primary" onClick={() => window.location.assign(target)}>
        {label}
      </Button>
      {hint && (
        <Typography variant="caption">
          {hint}
        </Typography>
      )}
    </Stack>
  );
}
