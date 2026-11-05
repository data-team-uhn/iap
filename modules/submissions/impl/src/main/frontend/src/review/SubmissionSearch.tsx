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

import SearchOutlined from "@mui/icons-material/SearchOutlined";
import {
  Box,
  ClickAwayListener,
  InputAdornment,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  TextField,
  Typography,
} from "@mui/material";

import type { Hit } from "./searchSubmission";

// PRETOTYPE. Searching a whole submission, answers and documents, with what it finds listed under the field. In
// the app bar it starts narrow, leaving room for the title beside it, and widens while it is used.
export default function SubmissionSearch({ query, hits, inBar = false, onQuery, onOpen, onClose, onPick }: {
  query: string;
  hits: Hit[] | undefined;
  inBar?: boolean;
  onQuery: (query: string) => void;
  onOpen: () => void;
  onClose: () => void;
  onPick: (hit: Hit) => void;
}) {
  return (
    <ClickAwayListener onClickAway={onClose}>
      <Box sx={{ position: "relative", width: inBar ? { md: 280 } : { xs: "100%", sm: 380 },
        transition: theme => theme.transitions.create("width"), "&:focus-within": inBar ? { width: { md: 420 } } : {} }}>
        <TextField
          fullWidth size="small" value={query} placeholder="Search this submission"
          onChange={event => {
            onQuery(event.target.value);
            onOpen();
          }}
          onFocus={onOpen}
          sx={inBar ? { "& .MuiInputBase-root": { bgcolor: "background.paper" } } : undefined}
          slotProps={{
            input: { startAdornment: <InputAdornment position="start"><SearchOutlined /></InputAdornment> },
            htmlInput: { "aria-label": "Search the submission" },
          }}
        />
        {hits && (
          <Paper elevation={8} sx={{ position: "absolute", zIndex: "modal", left: 0, right: 0, mt: 0.5,
            maxHeight: 420, overflowY: "auto" }}>
            {hits.length === 0 && <Typography sx={{ p: 2 }} variant="body2">Nothing found yet</Typography>}
            <List dense>
              {hits.map(hit => (
                <ListItemButton key={`${hit.requirement}-${hit.question ?? hit.page ?? ""}`} onClick={() => onPick(hit)}>
                  <ListItemText primary={hit.where} secondary={hit.snippet} />
                </ListItemButton>
              ))}
            </List>
          </Paper>
        )}
      </Box>
    </ClickAwayListener>
  );
}
