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
 * What the server found out about the requested path while it was rendering this page. A path can be missing
 * because it never existed or because it was deleted, and the two deserve different answers — the archive holding
 * that answer is readable by almost nobody, so the server looks for us and decides what to say. It says it in the
 * markup it was already sending, which is why none of this is fetched: see `DeletionMetadata`, and the
 * `data-deleted-*` attributes the 404 error handler carries.
 */
interface PageNotFoundProps {
  /** When the path was deleted, ISO-8601. Absent unless it was, so its presence is what says it was. */
  deletedAt?: string;
  /** Who deleted it. Only disclosed to a reader who can read the archive entry. */
  deletedBy?: string;
  /** Where to look at the entry. Only disclosed to a reader who can read it. */
  entryUrl?: string;
}

// The date a reader is shown, or null when the attribute holds something unreadable — "deleted", with no date,
// is still worth saying.
function deletionDate(deletedAt: string): string | null {
  const date = new Date(deletedAt);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}

export default function PageNotFound({ deletedAt, deletedBy, entryUrl }: PageNotFoundProps) {
  // Where a lost reader is sent, and what the button offers them, are per-deployment configuration rather than
  // anything about this request, so they arrive the way the rest of the configuration does: as `<meta>` tags
  // emitted from /libs/iap/conf. An empty value falls back with the rest, which is why `||` and not `??`.
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
        { /* `component` because MUI renders subtitle1 as an h6, and neither of these is a heading:
             they would join the page's outline between the title and nothing at all. */ }
        {deletedBy && <Typography variant="subtitle1" component="p" color="textSecondary">
          Deleted by {deletedBy}
        </Typography> }
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
