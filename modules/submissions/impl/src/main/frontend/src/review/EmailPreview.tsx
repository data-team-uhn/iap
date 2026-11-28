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
import { Box, Divider, Stack, Typography } from "@mui/material";

import type { IssuesEmail } from "./notifyEmail";

// PRETOTYPE. The study team's email as they will get it: who it is from and to, its subject, and what it says
export default function EmailPreview({ email }: { email: IssuesEmail }) {
  const field = (label: string, value: string) => (
    <>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>{label}</Typography>
      <Typography variant="body2">{value}</Typography>
    </>
  );
  return (
    <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "background.paper" }}>
      <Box sx={{ display: "grid", gridTemplateColumns: "auto minmax(0, 1fr)", columnGap: 1.5, rowGap: 0.5, p: 2 }}>
        {field("From", email.from)}
        {field("To", email.to)}
        {field("Subject", email.subject)}
      </Box>
      <Divider />
      <Stack spacing={2} sx={{ p: 2, typography: "body2" }}>
        <span>Dear {email.to},</span>
        <span>{email.opening}</span>
        {email.sections.map(section => (
          <Box key={section.heading}>
            <Typography variant="subtitle2" component="h4" sx={{ fontWeight: "fontWeightBold", mb: 0.5 }}>
              {section.heading}
            </Typography>
            <Stack spacing={0.75}>
              {section.points.map(point => <span key={point}>{point}</span>)}
            </Stack>
          </Box>
        ))}
        <Box component="span" sx={{ color: "text.secondary" }}>{email.closing}</Box>
      </Stack>
    </Box>
  );
}
