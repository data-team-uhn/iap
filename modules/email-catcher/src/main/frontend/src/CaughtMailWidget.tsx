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

import { Box, Chip, Skeleton, Stack, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type CatcherStatus, fetchCatcherStatus } from "./caughtMailApi";

/**
 * The dashboard summary of the mail catcher: whether it is on, and how much it has caught.
 *
 * Both halves are needed to say anything at all. A count on its own cannot distinguish "nothing has
 * been sent" from "everything that was sent went out by mail", and those call for opposite reactions
 * from somebody who came to the dashboard to check whether a notification worked.
 */
function CaughtMailWidget() {
  const doFetch = useAuthenticatedFetch();
  const [ status, setStatus ] = useState<CatcherStatus | null>(null);
  const [ unavailable, setUnavailable ] = useState(false);
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchCatcherStatus(doFetch)
      .then(result => { if (!cancelled) { setStatus(result); } })
      .catch(() => { if (!cancelled) { setUnavailable(true); } })
      .finally(() => { if (!cancelled) { setSettled(true); } });
    return () => { cancelled = true; };
  }, [ doFetch ]);

  if (!settled) {
    return <Skeleton variant="rounded" height={96} aria-label="Loading the caught mail summary" />;
  }

  // Reaching the administration console is not the same as being allowed to read /CaughtMail, and
  // an "Off" with no messages would be a claim rather than an absence of one
  if (unavailable || status === null) {
    return (
      <Typography variant="body2" color="text.secondary">
        The caught mail is not available to you.
      </Typography>
    );
  }

  return (
    <Box>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Typography variant="body2" color="text.secondary">Catching mail</Typography>
        <Chip
          size="small"
          label={status.enabled ? "On" : "Off"}
          color={status.enabled ? "success" : "default"}
        />
      </Stack>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline", mt: 1 }}>
        <Typography variant="body2" color="text.secondary">Caught so far</Typography>
        <Typography variant="h6" component="span">{String(status.total)}</Typography>
      </Stack>
      {!status.enabled && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {status.total > 0
            ? "Mail is being delivered normally now; these were caught earlier."
            : "Mail is being delivered normally, so nothing new will appear here."}
        </Typography>
      )}
      {status.enabled && status.total === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Nothing has been sent yet.
        </Typography>
      )}
    </Box>
  );
}

export default CaughtMailWidget;
