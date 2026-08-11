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

import EditIcon from "@mui/icons-material/Edit";
import VisibilityIcon from "@mui/icons-material/Visibility";
import { Box, IconButton, Tooltip } from "@mui/material";
import { useNavigate } from "react-router";

import DeleteItem from "@iap/deletion/DeleteItem";

interface SubmissionActionsProps {
  // The submission's repository path; absent only for a row the server could not identify
  path?: string;
  // The submission's title, used to name it in the delete confirmation
  title?: string;
  onDeleted?: () => void;
}

// The actions offered on one row of a submissions listing.
//
// Clicks are kept from reaching the row, which would otherwise navigate to the submission: the
// whole row is a link, so every control sitting inside one has to say that it is not part of it.
function SubmissionActions({ path, title, onDeleted }: SubmissionActionsProps) {
  const navigate = useNavigate();

  if (!path) {
    return null;
  }

  return (
    <Box
      sx={{ display: "flex", alignItems: "center" }}
      onClick={event => event.stopPropagation()}
    >
      <Tooltip title="View">
        <IconButton size="small" aria-label="View" onClick={() => void navigate(path)}>
          <VisibilityIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      {/* The same page, asked for by extension. Whether it can actually be edited is the server's
          answer, given by the form it serves; offering the action to somebody who may not edit costs
          them a page rather than a refused save. */}
      <Tooltip title="Edit">
        <IconButton size="small" aria-label="Edit" onClick={() => void navigate(`${path}.edit`)}>
          <EditIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      <DeleteItem
        path={path}
        name={title}
        type="submission"
        variant="icon"
        size="small"
        onDeleted={onDeleted}
      />
    </Box>
  );
}

export default SubmissionActions;
