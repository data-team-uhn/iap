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
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

type FormattedTextProps = Omit<TypographyProps, "children"> & {
  children?: string;
};

// Renders GitHub-flavored markdown as plain semantic HTML inside a Typography wrapper, so
// the text inherits the surrounding font, size and color. Raw HTML in the markdown source
// is intentionally not rendered.
const FormattedText = ({ children, ...typographyProps }: FormattedTextProps) => {
  return (
    <Typography component="div" {...typographyProps} >
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </Typography>
  );
}

export default FormattedText;
