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

import { useId } from "react";

import AltRouteOutlinedIcon from "@mui/icons-material/AltRouteOutlined";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import { ButtonBase, Stack, Typography } from "@mui/material";

interface AppliesWhenLineProps {
  children: string;
  // How to change it, where it can be changed: the line is then what is pressed to change it
  onEdit?: () => void;
}

const LINE = {
  alignItems: "flex-start", alignSelf: "flex-start", bgcolor: "background.muted", borderRadius: 1, px: 1, py: 0.5,
  gap: 0.75, textAlign: "start",
} as const;

// When something applies, as the line shown on it: where it is listed, and as its condition is edited
function AppliesWhenLine({ children, onEdit }: AppliesWhenLineProps) {
  const id = useId();
  const content = (
    <>
      <AltRouteOutlinedIcon fontSize="small" sx={{ color: "text.secondary", mt: 0.25 }} />
      <Typography id={id} variant="body2">{children}</Typography>
    </>
  );
  if (!onEdit) {
    return <Stack direction="row" sx={LINE}>{content}</Stack>;
  }
  return (
    <ButtonBase aria-label="Change when it applies" aria-describedby={id} onClick={onEdit}
      sx={{ ...LINE, "&:hover": { bgcolor: "action.hover" } }}>
      {content}
      <EditOutlinedIcon fontSize="small" sx={{ color: "text.secondary", mt: 0.25 }} />
    </ButtonBase>
  );
}

export default AppliesWhenLine;
