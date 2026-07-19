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

const UIXP_FINDER_URL = "/uixp";

type ExtensionPointProps = {
  // Extension Point ID (e.g. iap/coreUI/sidebar/entry).
  path: string;
  // Called with the parsed JSON when the extension resolves to a JSON object.
  callback?: (json: unknown) => void;
};

// Component that allows the user to insert an extension from the given URL.
//
// Required props:
//  path: Extension Point ID (e.g. iap/coreUI/sidebar/entry).
// Optional props:
//  callback: function to callback with the json data if the extension is a json object
//
// Sample usage:
// <ExtensionPoint
//    path="/testRig.js"
//    />
function ExtensionPoint(props: ExtensionPointProps) {
  const { path, callback } = props;
  const [ renderedResponse, setRenderedResponse ] = useState<ReactNode>(null);
  const [ initialized, setInitialized ] = useState(false);

  // Fetch the extension, called once on load
  const fetchExtension = (url: string) => {
    setInitialized(true);

    // From the extension point path, locate the URL of the ExtensionPoint
    const uixpFinder = new URL(`${UIXP_FINDER_URL}?uixp=${url}`, window.location.origin);
    fetch(uixpFinder)
      .then(grabUIXP)
      .then(handleResponse)
      .catch(handleError);
  }

  // Parse the UIXP URL from our UIXP Finder
  const grabUIXP = (response: Response) => {
    if (!response.ok) {
      return Promise.reject(`Finding ExtensionPoint ${path} failed with response ${response.status}`);
    }

    return response.text().then( (url) => {
      const parsedURL = new URL(url, window.location.origin);
      return(fetch(parsedURL));
    });
  }

  // Parse the content from the given Response object
  const handleResponse = (response: Response) => {
    if (!response.ok) {
      return Promise.reject(`Fetching ExtensionPoint ${path} failed with response ${response.status}`);
    }

    // Check the headers to determine how to handle this respnse
    let contentType = response.headers.get('Content-Type') as string;

    // Truncate the ';charset=utf-8'
    const sepPos = contentType.indexOf(";");
    if (sepPos >= 0) {
      contentType = contentType.substring(0, sepPos);
    }

    // Determine what to do depending on the value of the output
    if (['text/javascript', 'application/javascript'].indexOf(contentType) >= 0) {
      // javascript -- evaluate as-is
      return(response.text().then( (text) => {
        // eslint-disable-next-line react-hooks/unsupported-syntax
        return(eval(text));
      }));
    } else if (contentType === 'application/json') {
      // json -- call the provided callback
      if (callback !== undefined) {
        return(response.json().then( (json) => callback(json)));
      } else {
        return(Promise.reject(
          `Fetching ExtensionPoint ${path} returned json data, but no callback was provided to its ExtensionPoint`
        ));
      }
    } else if (contentType === 'text/html') {
      // html -- include it inline
      return(response.text().then((text) => {
        setRenderedResponse((<div dangerouslySetInnerHTML={{ __html: text }}/>));
      }));
    } else {
      // Reject any other content type
      return(Promise.reject(`Fetching ExtensionPoint ${path} returned unknown contentType: ${contentType}`));
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
