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
import FitWidthIcon from "@mui/icons-material/WidthFull";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import ZoomOutIcon from "@mui/icons-material/ZoomOut";
import { IconButton } from "@mui/material";

// The zoom buttons, disabled until the document has opened. The last one sits flush with the row's end.
export default function ZoomControls({ disabled, onZoomIn, onZoomOut, onFitWidth }: {
  disabled: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitWidth: () => void;
}) {
  return (
    <>
      <IconButton aria-label="Zoom out" disabled={disabled} onClick={onZoomOut}>
        <ZoomOutIcon />
      </IconButton>
      <IconButton aria-label="Fit to width" disabled={disabled} onClick={onFitWidth}>
        <FitWidthIcon />
      </IconButton>
      <IconButton aria-label="Zoom in" edge="end" disabled={disabled} onClick={onZoomIn}>
        <ZoomInIcon />
      </IconButton>
    </>
  );
}
