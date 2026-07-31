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

import { Divider, Stack, Typography } from "@mui/material";

// A dashboard widget with a moderate amount of structured content — a weather summary — used to
// exercise how the dashboard tiles medium-sized widgets. The titled frame comes from the dashboard.
function WeatherWidget() {
  const stats = [
    { label: "High", value: "24°" },
    { label: "Low", value: "17°" },
    { label: "Humidity", value: "58%" },
    { label: "Wind", value: "12 km/h" },
  ];

  return (
    <Stack spacing={2}>
      <Typography variant="subtitle1" color="text.secondary">Toronto</Typography>
      <Stack direction="row" spacing={2} alignItems="baseline">
        <Typography variant="h3" component="p">21°</Typography>
        <Typography variant="body1" color="text.secondary">Partly cloudy</Typography>
      </Stack>
      <Divider />
      <Stack direction="row" justifyContent="space-between">
        {stats.map(stat => (
          <Stack key={stat.label} alignItems="center">
            <Typography variant="caption" color="text.secondary">{stat.label}</Typography>
            <Typography variant="body2">{stat.value}</Typography>
          </Stack>
        ))}
      </Stack>
    </Stack>
  );
}

export default WeatherWidget;
