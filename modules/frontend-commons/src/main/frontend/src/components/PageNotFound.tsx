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

import { Link, Typography } from '@mui/material';

import ErrorPage from './ErrorPage';

/**
 * What the reader may know about the deletion of the requested path. The server writes it into the 404 page's
 * `data-deleted-*` attributes. All three are undefined for a path nobody deleted, or one the reader may not know
 * about.
 */
interface PageNotFoundProps {
  /** When the path was deleted, as an ISO-8601 timestamp. */
  deletedAt?: string;
  /** The user id of who deleted it; undefined unless the reader can read the archive entry. */
  deletedBy?: string;
  /** The console route of the archive entry's page; undefined unless the reader can read the entry. */
  entryUrl?: string;
}

// The date as the reader's locale writes it; null if `deletedAt` is not a date.
function deletionDate(deletedAt: string): string | null {
  const date = new Date(deletedAt);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}

// The 404 page: says that the requested path was deleted, and when, or that it does not exist.
export default function PageNotFound({ deletedAt, deletedBy, entryUrl }: PageNotFoundProps) {
  // The button's target and label are configured under /libs/iap/conf, and reach the page as `<meta>` tags. An
  // empty value falls back to the default as well, hence `||` rather than `??`.
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  /* eslint-disable @typescript-eslint/prefer-nullish-coalescing */
  const redirectURL = meta("redirectURL") || "/";
  const redirectLabel = meta("redirectLabel") || "Go to the homepage";
  /* eslint-enable @typescript-eslint/prefer-nullish-coalescing */

  if (deletedAt) {
    const date = deletionDate(deletedAt);
    return (
      <ErrorPage
        errorCode="404"
        title="Deleted"
        message={date ? `This page was deleted on ${date}` : "This page was deleted"}
        buttonLink={redirectURL}
        buttonLabel={redirectLabel}
      >
        {deletedBy && <Typography variant="description" component="p">
          Deleted by {deletedBy}
        </Typography> }
        { /* `component`, because MUI renders subtitle1 as an h6, and this line is not a heading */ }
        {entryUrl && <Typography variant="subtitle1" component="p">
          <Link href={entryUrl}>View the archive entry</Link>
        </Typography> }
      </ErrorPage>
    );
  }

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
