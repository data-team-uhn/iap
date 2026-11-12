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

import { useState, type ReactNode } from "react";

import { readJson } from "@iap/frontend-commons/requestFailure";

const UIXP_FINDER_URL = "/uixp";

interface ExtensionPointProps {
  // The `ext:pointId` of the extension point, such as `iap/coreUI/view`
  path: string;
  // Called with the parsed JSON, when the extension point answers with JSON
  callback?: (json: unknown) => void;
}

// Fetches an extension point by its `ext:pointId`. It asks the `/uixp` finder for the point's path,
// then fetches that path. An answer in JavaScript is evaluated, JSON goes to `callback`, and HTML is
// shown in place. Any other answer, and any failure, is logged.
function ExtensionPoint(props: ExtensionPointProps) {
  const { path, callback } = props;
  const [ renderedResponse, setRenderedResponse ] = useState<ReactNode>(null);
  const [ initialized, setInitialized ] = useState(false);

  // Called once, on the first render
  const fetchExtension = (url: string) => {
    setInitialized(true);

    const uixpFinder = new URL(`${UIXP_FINDER_URL}?uixp=${url}`, window.location.origin);
    fetch(uixpFinder)
      .then(grabUIXP)
      .then(handleResponse)
      .catch(handleError);
  }

  // Fetches the path the finder answered with
  const grabUIXP = (response: Response) => {
    if (!response.ok) {
      return Promise.reject(new Error(`Finding ExtensionPoint ${path} failed with response ${response.status}`));
    }

    return response.text().then( (url) => {
      const parsedURL = new URL(url, window.location.origin);
      return(fetch(parsedURL));
    });
  }

  // Handles the extension point's answer by its content type
  const handleResponse = (response: Response) => {
    if (!response.ok) {
      return Promise.reject(new Error(`Fetching ExtensionPoint ${path} failed with response ${response.status}`));
    }

    let contentType = response.headers.get('Content-Type') ?? '';

    // Drop a parameter such as `;charset=utf-8`
    const sepPos = contentType.indexOf(";");
    if (sepPos >= 0) {
      contentType = contentType.substring(0, sepPos);
    }

    if (['text/javascript', 'application/javascript'].includes(contentType)) {
      return(response.text().then( (text) => {
        // eslint-disable-next-line react-hooks/unsupported-syntax
        return(eval(text) as unknown);
      }));
    } else if (contentType === 'application/json') {
      if (callback !== undefined) {
        return(readJson<unknown>(response).then( (json) => callback(json)));
      } else {
        return(Promise.reject(new Error(
          `Fetching ExtensionPoint ${path} returned json data, but no callback was provided to its ExtensionPoint`
        )));
      }
    } else if (contentType === 'text/html') {
      return(response.text().then((text) => {
        setRenderedResponse((<div dangerouslySetInnerHTML={{ __html: text }}/>));
      }));
    } else {
      return(Promise.reject(new Error(`Fetching ExtensionPoint ${path} returned unknown contentType: ${contentType}`)));
    }
  }

  const handleError = (error: unknown) => {
    console.error(error);
  }

  if (!initialized) {
    fetchExtension(path);
  }


  return renderedResponse;
}

export default ExtensionPoint;
