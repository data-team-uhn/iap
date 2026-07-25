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

import { Container, Paper, Stack, Typography } from '@mui/material';

import Logo from '@iap/frontend-commons/components/Logo';

import LoginForm from './LoginForm';
import { loginRedirectPath } from './loginRedirect';

// The landing page shown to unauthenticated visitors: the application branding and a
// sign-in action. Informational content about the platform belongs here too, so that
// this page keeps working as the landing page when authentication moves to an external
// identity provider and the credentials form is replaced with a redirect.
export default function LoginPage() {
  const appName = document.querySelector<HTMLMetaElement>('meta[name="title"]')?.content;

  return (
    <Container maxWidth="xs" sx={{ paddingBlock: 8 }}>
      <Stack spacing={4}>
        <Logo sx={{ display: 'block', inlineSize: '100%', maxInlineSize: 240, marginInline: 'auto' }} />
        <Paper sx={{ padding: 3 }}>
          <Stack spacing={3}>
            <Typography variant="h5" component="h1">
              {appName ? `Sign in to ${appName}` : "Sign in"}
            </Typography>
            <LoginForm onSuccess={() => window.location.assign(loginRedirectPath())} />
          </Stack>
        </Paper>
      </Stack>
    </Container>
  );
}
