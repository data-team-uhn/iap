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

import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

interface EmptyStateProps {
  title: string;
  body: string;
  /** The way out, where there is one. */
  actionLabel?: string;
  onAction?: () => void;
}

/** Why there is nothing to show, and what to do about it. */
export default function EmptyState({ title, body, actionLabel, onAction }: EmptyStateProps) {
  return (
    <Box sx={{ px: 3, py: "70px", textAlign: "center" }}>
      <Typography variant="subtitle1" component="p" sx={{ mb: 1 }}>{title}</Typography>
      <Typography variant="body2" sx={{ color: "text.secondary", maxWidth: 420, mx: "auto" }}>
        {body}
      </Typography>
      {actionLabel && onAction && (
        <Link component="button" type="button" onClick={onAction} sx={{ mt: 2 }}>
          {actionLabel}
        </Link>
      )}
    </Box>
  );
}
