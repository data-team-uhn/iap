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

import HourglassBottomOutlined from "@mui/icons-material/HourglassBottomOutlined";
import PauseCircleOutlined from "@mui/icons-material/PauseCircleOutlined";
import WarningAmberOutlined from "@mui/icons-material/WarningAmberOutlined";
import { Stack, Typography } from "@mui/material";

import { getCountdown } from "./reviewModel";

// Under this many days left, the countdown asks for attention
const SOON = 3;

// PRETOTYPE. How long the reviewers have left on a study, in red once it is overdue.
export default function Countdown({ due, pausedAt, variant = "body2" }: {
  due: unknown;
  pausedAt: unknown;
  variant?: "body2" | "subtitle1";
}) {
  const countdown = getCountdown(due, pausedAt);
  if (!countdown) {
    return null;
  }
  const overdue = countdown.days < 0;
  const color = countdown.paused ? "text.secondary" : overdue ? "error.main"
    : countdown.days <= SOON ? "warning.main" : "text.primary";
  const Icon = countdown.paused ? PauseCircleOutlined : overdue ? WarningAmberOutlined : HourglassBottomOutlined;
  return (
    <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, color }}>
      <Icon fontSize="small" />
      <Typography variant={variant} sx={{ color, fontWeight: overdue ? "fontWeightBold" : undefined, whiteSpace: "nowrap" }}>
        {countdown.label}
      </Typography>
    </Stack>
  );
}
