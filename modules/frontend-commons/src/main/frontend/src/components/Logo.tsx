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

import type { CSSProperties, ElementType } from 'react';

import { Box } from '@mui/material';
import { makeStyles } from 'tss-react/mui';

type LogoProps = {
  component?: ElementType;
  mode?: "Light" | "Dark";
  className?: string;
  maxWidth?: string;
  disableAffiliation?: boolean;
  // Any extra props are forwarded to the rendered container component.
  [key: string]: unknown;
};

const useStyles = makeStyles()(theme => ({
  logo : {
    "& > img" : {
      maxWidth: "240px",
      width: "100%",
    },
    "@media (max-height: 725px)" : {
      "& > img" : {
        maxHeight: "70px",
      },
    },
  },
  doubleLogo : {
    display: "flex",
    gap: theme.spacing(4),
    justifyContent: "space-between",
    alignItems: "center",
    flexWrap: "wrap-reverse",
    maxWidth: "600px !important",
    "& > img" : {
      width: `calc(50% - ${theme.spacing(4)})`,
      minWidth: "100px",
      margin: theme.spacing(1, 0),
    },
  },
}));

export default function Logo(props: LogoProps) {
  const {
    component = Box,
    mode = "Light",
    className,
    maxWidth,
    disableAffiliation,
    ...rest
  } = props;

  const { classes } = useStyles();

  const appName = document.querySelector<HTMLMetaElement>('meta[name="title"]')?.content;
  const logo = document.querySelector<HTMLMetaElement>(`meta[name="logo${mode}"]`)?.content;
  const affiliationLogo = document.querySelector<HTMLMetaElement>(`meta[name="affiliationLogo${mode}"]`)?.content;
  const withAffiliation = !disableAffiliation && !!affiliationLogo;

  const Component = component;
  const style: CSSProperties = typeof(maxWidth) != "undefined" ? { maxWidth: maxWidth } : {};
  const classNames = withAffiliation ? [classes.doubleLogo] : [classes.logo];
  if (className) classNames.push(className);

  return (
    <Component className={classNames.join(' ')} {...rest} >
      <img src={logo} alt={appName} style={style} />
      {withAffiliation && <img src={affiliationLogo} alt="" style={style} />}
    </Component>
  );
}
