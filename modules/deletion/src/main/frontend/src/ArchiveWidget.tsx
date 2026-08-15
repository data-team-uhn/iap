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

import { useEffect, useState } from "react";

import { Box, Button, Skeleton, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { fetchArchiveSummary, type ArchiveSummary } from "./archiveApi";

/** The archive view's URL, as registered on the `iap/coreUI/view` extension point. */
const ARCHIVE_VIEW_URL = "/Archive";

// One counted period.
function Count({ label, value, approximate }: { label: string; value: number; approximate: boolean }) {
  return (
    <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="h6" component="span">
        {approximate ? `${String(value)}+` : String(value)}
      </Typography>
    </Stack>
  );
}

// A dashboard widget summarizing the archive: how many deletions were recorded recently and in
// total, and a way through to the full view where they can be restored or purged.
//
// The archive is readable only by users who can see it, so this widget can be shown to somebody the
// server will refuse — wearing a persona is not the same as holding the rights that go with it. It
// says so plainly rather than showing zeros, which would be a claim that the archive is empty.
function ArchiveWidget() {
  const doFetch = useAuthenticatedFetch();
  const [ summary, setSummary ] = useState<ArchiveSummary | null>(null);
  const [ unavailable, setUnavailable ] = useState(false);
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchArchiveSummary(doFetch)
      .then(result => { if (!cancelled) { setSummary(result); } })
      .catch(() => { if (!cancelled) { setUnavailable(true); } })
      .finally(() => { if (!cancelled) { setSettled(true); } });
    return () => { cancelled = true; };
  }, [ doFetch ]);

  if (!settled) {
    return <Skeleton variant="rounded" height={120} aria-label="Loading the archive summary" />;
  }

  if (unavailable || !summary) {
    return (
      <Typography variant="body2" color="text.secondary">
        The archive is not available to you.
      </Typography>
    );
  }

  return (
    <Stack spacing={1}>
      <Box>
        <Count label="Archived in the last 24 hours" value={summary.last24Hours} approximate={summary.approximate} />
        <Count label="Archived in the last 7 days" value={summary.lastWeek} approximate={summary.approximate} />
        <Count label="Archived in total" value={summary.total} approximate={summary.approximate} />
      </Box>
      <Box>
        <Button component={RouterLink} to={ARCHIVE_VIEW_URL} size="small">
          Open the archive
        </Button>
      </Box>
    </Stack>
  );
}

export default ArchiveWidget;
