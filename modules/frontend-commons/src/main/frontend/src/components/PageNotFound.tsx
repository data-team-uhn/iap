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

import { useEffect, useState } from 'react';

import ErrorPage from './ErrorPage';

// The shape of /RedirectURL.json, served by the login/redirect servlet.
interface RedirectInfo {
  RedirectURL: string;
  RedirectLabel: string;
}

export default function PageNotFound() {
  const [redirectURL, setRedirectURL] = useState("/");
  const [redirectLabel, setRedirectLabel] = useState("Go to the homepage");

  // Grab the redirect URL on first rerender
  useEffect(() => {
    fetch("/RedirectURL.json")
      .then((response) => response.ok ? response.json() as Promise<RedirectInfo> : Promise.reject(new Error(`Failed to load RedirectURL.json: ${response.status}`)))
      .then((json) => { setRedirectURL(json.RedirectURL); setRedirectLabel(json.RedirectLabel); })
      .catch(() => { /* keep the default redirect */ });
  }, []);

  return (
    <ErrorPage
      errorCode="404"
      title="Not found"
      message="The page you are trying to reach does not exist"
      buttonLink={redirectURL}
      buttonLabel={redirectLabel}
    />
  );
}
