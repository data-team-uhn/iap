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

import { MEGABYTE, formatSize } from "./fileValidation/contentCheck";
import { checkDoc } from "./fileValidation/docCheck";
import { checkDocx } from "./fileValidation/docxCheck";
import { checkPdf } from "./fileValidation/pdfCheck";

import type { ContentCheck } from "./fileValidation/contentCheck";

export { MEGABYTE };

// Checks an upload in the browser so a bad file is refused in a moment, not after a slow upload. It
// only advises: the server checks again.

// The server's limit (AttachDocumentHandler.MAX_FILE_BYTES): change both together.
export const MAX_FILE_SIZE = 50 * MEGABYTE;

// Past this, the parse takes long enough that a person would think it had failed.
export const MAX_PDF_PAGES = 500;

// The parser's own limit (IAP_MAX_EXPANDED_BYTES). A .docx is a zip, and a small one can unzip to
// gigabytes.
export const MAX_UNZIPPED_SIZE = 512 * MEGABYTE;

// The types the pipeline reads, by the extension that names them. The server keeps the same map
// (AttachDocumentHandler.TYPE_BY_EXTENSION): change both together.
export const MIME_TYPE_BY_EXTENSION = {
  ".pdf": "application/pdf",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".doc": "application/msword",
} as const;

// For a caller that takes only what the pipeline reads
export const PIPELINE_TYPES: string[] = Object.values(MIME_TYPE_BY_EXTENSION);

const MIME_TYPES = new Map<string, string>(Object.entries(MIME_TYPE_BY_EXTENSION));

const EXTENSIONS = new Map<string, string>(
  Object.entries(MIME_TYPE_BY_EXTENSION).map(([ extension, type ]) => [ type, extension ]));

// What a browser sends for a file whose type it does not know
const GENERIC_TYPE = "application/octet-stream";

/** Limits a caller can tighten or loosen; each one left out takes the default above. */
export interface UploadLimits {
  /** The largest file taken, in bytes. */
  maxFileSize?: number;
  /** The most pages a PDF may have. */
  maxPdfPages?: number;
  /** The most a .docx may unzip to, in bytes. */
  maxUnzippedSize?: number;
}

// The lowercase extension including the dot, or undefined when the name carries none.
export function getFileExtension(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0 || dot === fileName.length - 1) {
    return undefined;
  }
  return fileName.slice(dot).toLowerCase();
}

// Each format the pipeline reads has a check of its own, in fileValidation/. Any other file passes.
async function checkContent(
  file: File,
  extension: string | undefined,
  limits: Required<Pick<UploadLimits, "maxPdfPages" | "maxUnzippedSize">>,
  signal?: AbortSignal,
): Promise<ContentCheck> {
  if (extension === ".pdf") {
    return checkPdf(file, limits.maxPdfPages, signal);
  }
  if (extension === ".docx") {
    return checkDocx(file, limits.maxUnzippedSize, signal);
  }
  if (extension === ".doc") {
    return checkDoc(file);
  }
  return { valid: true };
}

// The file's type as the server reads it (AttachDocumentHandler#getMimeType): lower case and without
// parameters. The extension names it when the browser sent no type or only the generic one.
function getMimeType(file: File, extension: string | undefined): string | undefined {
  const declared = file.type.split(";", 1)[0].trim().toLowerCase();
  if (declared !== "" && declared !== GENERIC_TYPE) {
    return declared;
  }
  const fromName = extension === undefined ? undefined : MIME_TYPES.get(extension);
  return fromName ?? (declared === "" ? undefined : declared);
}

// A known MIME type reads as its extension, so a person sees ".docx" rather than the long type name
function describeWrongType(file: File, accepted: string[]): string {
  const names = new Set(accepted.map(type => EXTENSIONS.get(type.toLowerCase()) ?? type.toLowerCase()));
  return `${file.name} is not a file of an accepted type: ${[ ...names ].join(", ")}.`;
}

/**
 * What is wrong with an upload, or undefined when nothing is.
 *
 * @param file the file the person picked
 * @param accepted the MIME types taken, as the server compares them; empty takes any type
 * @param limits the size, length and unzipped-size limits, where the defaults do not fit
 * @param signal stops the check, which then rejects with the signal's reason
 */
export async function validateUpload(
  file: File,
  accepted: string[] = [],
  limits: UploadLimits = {},
  signal?: AbortSignal,
): Promise<string | undefined> {
  signal?.throwIfAborted();
  const maxFileSize = limits.maxFileSize ?? MAX_FILE_SIZE;
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > maxFileSize) {
    const size = formatSize(file.size, Math.ceil);
    return `${file.name} is ${size}, and the limit is ${formatSize(maxFileSize, Math.floor)}.`;
  }
  const extension = getFileExtension(file.name);
  const type = getMimeType(file, extension);
  if (accepted.length > 0 && !accepted.some(entry => entry.toLowerCase() === type)) {
    return describeWrongType(file, accepted);
  }
  const content = await checkContent(file, extension, {
    maxPdfPages: limits.maxPdfPages ?? MAX_PDF_PAGES,
    maxUnzippedSize: limits.maxUnzippedSize ?? MAX_UNZIPPED_SIZE,
  }, signal);
  return content.valid ? undefined : `${file.name}: ${content.error}`;
}
