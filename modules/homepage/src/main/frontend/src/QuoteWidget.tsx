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

import { Stack, Typography } from "@mui/material";

// A dashboard widget with a longer block of text — a "quote of the day" — used to exercise how the
// dashboard tiles widgets whose content is tall. The titled frame comes from the dashboard.
function QuoteWidget() {
  return (
    <Stack spacing={2}>
      <Typography variant="body1" component="blockquote" sx={{ fontStyle: "italic" }}>
        &ldquo;The best way to predict the future is to invent it. Really smart people with
        reasonable funding can do just about anything that doesn&rsquo;t violate too many of
        Newton&rsquo;s laws. The reason it hasn&rsquo;t happened yet is that the people who could
        do it are not the people who want to do it, and vice versa.&rdquo;
      </Typography>
      <Typography variant="body2" color="text.secondary">
        — Alan Kay, computer scientist, on the founding ideas of personal computing
      </Typography>
    </Stack>
  );
}

export default QuoteWidget;
