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
// The zoom buttons: Zoom out, Fit to width and Zoom in.
import ExpandIcon from "@mui/icons-material/Expand";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import ZoomOutIcon from "@mui/icons-material/ZoomOut";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";

// The zoom buttons, disabled until the document has opened. The last one sits flush with the row's end.
export default function ZoomControls({ disabled, onZoomIn, onZoomOut, onFitWidth }: {
  disabled: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitWidth: () => void;
}) {
  return (
    <>
      <ActionIcon label="Zoom out" icon={<ZoomOutIcon />} size="medium" disabled={disabled} onClick={onZoomOut} />
      <ActionIcon
        label="Fit to width"
        // Expand points up and down; a quarter turn makes it point to the sides
        icon={<ExpandIcon sx={{ transform: "rotate(90deg)" }} />}
        size="medium"
        disabled={disabled}
        onClick={onFitWidth}
      />
      <ActionIcon
        label="Zoom in" icon={<ZoomInIcon />} size="medium" edge="end" disabled={disabled} onClick={onZoomIn}
      />
    </>
  );
}
