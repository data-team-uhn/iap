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

import { Typography, type TypographyProps } from "@mui/material";
import MDEditor from '@uiw/react-md-editor';
import { makeStyles } from 'tss-react/mui';

type FormattedTextProps = Omit<TypographyProps, "children"> & {
  children?: string;
};

const useStyles = makeStyles()(() => ({
  markdown: {
    "&.wmde-markdown" : {
      background: "transparent",
      color: "inherit",
      fontSize: "inherit",
      fontFamily: "inherit",
      "& .anchor" : {
        display: "none",
      },
      "& img": {
        background: "transparent"
      }
    },
  }
}));

const FormattedText = ({ children, ...typographyProps }: FormattedTextProps) => {
  const { classes } = useStyles();

  return (
    <Typography component="div" {...typographyProps} >
      <MDEditor.Markdown classes={classes} className={classes.markdown} source={children} />
    </Typography>
  );
}

export default FormattedText;
