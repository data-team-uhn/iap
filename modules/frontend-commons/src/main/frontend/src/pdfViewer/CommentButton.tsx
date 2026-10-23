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
import AddCommentOutlinedIcon from "@mui/icons-material/AddCommentOutlined";
import { Button } from "@mui/material";

import { COMMENT_BUTTON_HEIGHT } from "./pdfViewerModel";

import type { MarkSpan } from "./marksModel";
import type { SelectedText } from "./useSelectedText";

// The button offering to comment on the text selected, at the place given. Pressing it keeps the focus
// and the selection, and holds what is selected, since a tap clears the selection on some phones.
export default function CommentButton({ at, onPress, onComment }: {
  at: SelectedText;
  onPress: () => void;
  onComment: (spans: MarkSpan[]) => void;
}) {
  const { top, left, right } = at.place;
  return (
    <Button
      variant="contained"
      startIcon={<AddCommentOutlinedIcon />}
      onPointerDown={event => {
        event.preventDefault();
        onPress();
      }}
      onClick={() => onComment(at.spans)}
      style={{ top, left, right }}
      sx={{ position: "absolute", zIndex: 1, height: COMMENT_BUTTON_HEIGHT, boxShadow: 4, whiteSpace: "nowrap" }}
    >
      Comment
    </Button>
  );
}
