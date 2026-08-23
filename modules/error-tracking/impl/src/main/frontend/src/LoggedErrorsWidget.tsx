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

import { Box, Skeleton, Stack, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type TriageCounts, fetchTriageCounts } from "./errorTrackingApi";

// One counted figure. The count that matters is spelled out rather than shown as a bare number,
// because a widget is read at a glance and "3" beside "Needing attention" is a different thing
// from "3" beside "Recorded in total".
function Count({ label, value, approximate, emphasis }: {
  label: string;
  value: number;
  approximate: boolean;
  emphasis?: boolean;
}) {
  return (
    <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography
        variant="h6"
        component="span"
        // Red only when something is actually outstanding: a permanently red widget stops being read
        color={emphasis === true && value > 0 ? "error.main" : "text.primary"}
      >
        {approximate ? `${String(value)}+` : String(value)}
      </Typography>
    </Stack>
  );
}

/**
 * An administration console widget summarizing the recorded errors: how many still need attention,
 * and how many there are altogether. The way through to the full list is the frame's own header
 * action, from the extension's `ext:actionLabel` and `ext:targetURL`, so this renders only the
 * summary.
 *
 * The console is reached only by administrators, but reaching it is not the same as holding the
 * rights to read `/LoggedErrors`, so the summary can still be refused. It says so plainly rather
 * than showing zeros, which would be the opposite claim — that nothing has ever gone wrong.
 */
function LoggedErrorsWidget() {
  const doFetch = useAuthenticatedFetch();
  const [ counts, setCounts ] = useState<TriageCounts | null>(null);
  const [ unavailable, setUnavailable ] = useState(false);
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchTriageCounts(doFetch)
      .then(result => { if (!cancelled) { setCounts(result); } })
      .catch(() => { if (!cancelled) { setUnavailable(true); } })
      .finally(() => { if (!cancelled) { setSettled(true); } });
    return () => { cancelled = true; };
  }, [ doFetch ]);

  if (!settled) {
    return <Skeleton variant="rounded" height={96} aria-label="Loading the error summary" />;
  }

  if (unavailable || counts === null) {
    return (
      <Typography variant="body2" color="text.secondary">
        The recorded errors are not available to you.
      </Typography>
    );
  }

  return (
    <Box>
      <Count
        label="Needing attention"
        value={counts.needingAttention}
        approximate={counts.approximate}
        emphasis
      />
      <Count label="Recorded in total" value={counts.total} approximate={counts.approximate} />
      {counts.total === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Nothing has been recorded yet.
        </Typography>
      )}
      {counts.total > 0 && counts.needingAttention === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Everything recorded has been dealt with.
        </Typography>
      )}
    </Box>
  );
}

export default LoggedErrorsWidget;
