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

import type { CSSProperties, ReactNode } from 'react';

import NavigationIcon from '@mui/icons-material/Navigation';
import { Box, Button, Grid, Stack, Typography, type BoxProps } from '@mui/material';
import { styled } from '@mui/material/styles';

import FormattedText from "./FormattedText";
import Logo from "./Logo";

type ErrorPageProps = Omit<BoxProps, "title"> & {
  errorCode?: ReactNode;
  errorCodeColor?: string;
  title?: ReactNode;
  titleColor?: string;
  message?: string;
  messageColor?: string;
  buttonLink?: string;
  buttonLabel?: ReactNode;
  textAlign?: CSSProperties["textAlign"];
};

const ErrorContainer = styled(Box)(({ theme }) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  padding: theme.spacing(12, 3, 3),
  maxWidth: 450,
  margin: '0 auto',
}));

export default function ErrorPage(props: ErrorPageProps) {
  const {
    errorCode,
    errorCodeColor,
    title,
    titleColor,
    message,
    messageColor,
    buttonLink,
    buttonLabel,
    textAlign = "left",
    ...rest
  } = props;

  // An explicitly empty color should fall back to the default too, not just a missing one, so
  // `||` (not `??`) is intentional for these three.
  /* eslint-disable @typescript-eslint/prefer-nullish-coalescing */
  const resolvedErrorCodeColor = errorCodeColor || "primary";
  const resolvedTitleColor = titleColor || "primary";
  const resolvedMessageColor = messageColor || "textSecondary";
  /* eslint-enable @typescript-eslint/prefer-nullish-coalescing */

  return (
    <ErrorContainer {...rest}>
      <Grid
        container
        spacing={6}
        sx={{
          // Grid's `direction` prop only accepts the row axis, so the column layout goes
          // through `flexDirection`. `spacing` sets the gap on both axes, so it applies
          // either way.
          flexDirection: "column",
          textAlign: textAlign,
          alignItems: "stretch",
          alignContent: "stretch",
        }}
      >
        <Grid>
          <Logo sx={{ display: 'block', inlineSize: '100%', maxInlineSize: 240 }} />
        </Grid>
        <Grid>
          <Stack spacing={2}>
            {errorCode && <Typography variant="h3" component="h1" color={resolvedErrorCodeColor}>
              {errorCode}
            </Typography> }
            {title && <Typography variant="h4" component="h2" color={resolvedTitleColor} sx={{ fontWeight: 'bold' }}>
              {title}
            </Typography> }
            {message && <FormattedText variant="subtitle1" color={resolvedMessageColor}>
              {message}
            </FormattedText> }
          </Stack>
        </Grid>
        { buttonLabel &&
            <Grid>
              <Button
                variant="contained"
                color="primary"
                startIcon={<NavigationIcon />}
                onClick={() => window.location.href = buttonLink ?? ""}
              >
                {buttonLabel}
              </Button>
            </Grid>
        }
      </Grid>
    </ErrorContainer>
  );
}
