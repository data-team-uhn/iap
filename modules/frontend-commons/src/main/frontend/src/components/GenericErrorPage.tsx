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

import { StrictMode } from 'react';

import { CssBaseline } from '@mui/material';
import { ThemeProvider, StyledEngineProvider } from '@mui/material/styles';
import { createRoot } from 'react-dom/client';

import ErrorPage from './ErrorPage';
import { appTheme } from "../appTheme";

const container = document.getElementById('main-error-container');
if (container) {
  createRoot(container).render(
    <StrictMode>
      <StyledEngineProvider injectFirst>
        <ThemeProvider theme={appTheme} defaultMode="system">
          <CssBaseline enableColorScheme />
          <ErrorPage
            errorCode={document.querySelector<HTMLMetaElement>('meta[name="statusCode"]')?.content}
            title={document.querySelector<HTMLMetaElement>('meta[name="statusMessage"]')?.content}
            message=""
          />
        </ThemeProvider>
      </StyledEngineProvider>
    </StrictMode>
  );
}
