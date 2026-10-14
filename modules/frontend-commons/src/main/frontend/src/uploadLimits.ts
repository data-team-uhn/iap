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

import type { UploadLimits } from "./fileValidation";
import type { AuthenticatedFetch } from "./reLogin";

// Where the server says how large a document may be
export const UPLOAD_LIMITS_URL = "/system/documents/limits";

const LIMIT_NAMES = [ "maxFileSize", "maxPdfPages", "maxUnzippedSize" ] as const;

/**
 * The server's upload limits, for `validateUpload`. A limit the server does not give is left out, and
 * so are all of them when the server cannot be asked or `signal` stops the request: the check then
 * uses its own defaults, since it only advises.
 */
export async function fetchUploadLimits(
  doFetch: AuthenticatedFetch, signal?: AbortSignal): Promise<UploadLimits> {
  try {
    const response = await doFetch(UPLOAD_LIMITS_URL, { signal });
    if (!response.ok) {
      throw new Error(`The server answered ${response.status}`);
    }
    const served = (await response.json()) as Partial<Record<string, unknown>>;
    const limits: UploadLimits = {};
    for (const name of LIMIT_NAMES) {
      const value = served[name];
      if (typeof value === "number" && Number.isFinite(value) && value > 0) {
        limits[name] = value;
      }
    }
    return limits;
  } catch (error) {
    console.error("Could not read the upload limits, so the defaults apply", error);
    return {};
  }
}
