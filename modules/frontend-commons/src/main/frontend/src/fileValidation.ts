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

import { checkDoc } from "./fileValidation/docCheck";
import { checkDocx } from "./fileValidation/docxCheck";
import { checkPdf } from "./fileValidation/pdfCheck";
import { MEGABYTE, formatSize } from "./fileValidation/size";
import { stopOnAbort } from "./fileValidation/stopOnAbort";

import type { ContentCheck } from "./fileValidation/contentCheck";

export { MEGABYTE } from "./fileValidation/size";

// Checks an upload in the browser, so a bad file is refused at once rather than after a slow
// upload. The server checks again.

// The server's limit (AttachDocumentHandler.MAX_FILE_BYTES): change both together.
export const MAX_FILE_SIZE = 50 * MEGABYTE;

// The parser's default page limit (IAP_MAX_INPUT_PAGES).
export const MAX_PDF_PAGES = 500;

// The parser's default limit (IAP_MAX_EXPANDED_BYTES). A .docx is a zip, and a small one can unzip
// to gigabytes.
export const MAX_UNZIPPED_SIZE = 512 * MEGABYTE;

// The types the pipeline reads, by the extension that names them. The server keeps the same map
// (AttachDocumentHandler.TYPE_BY_EXTENSION): change both together.
export const MIME_TYPE_BY_EXTENSION = {
  ".pdf": "application/pdf",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".doc": "application/msword",
} as const;

// The MIME types the pipeline reads, for a caller that accepts only those
export const PIPELINE_TYPES: string[] = Object.values(MIME_TYPE_BY_EXTENSION);

type PipelineExtension = keyof typeof MIME_TYPE_BY_EXTENSION;

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

/** How to check an upload; everything left out takes its default. */
export interface UploadCheckOptions {
  /** The MIME types accepted, such as `application/pdf`, ignoring case. Empty or left out accepts any. */
  accepted?: string[];
  /** The size, page and unzipped-size limits, where the defaults do not fit. */
  limits?: UploadLimits;
  /** Stops the check, which then rejects with the signal's reason. */
  signal?: AbortSignal;
  /** How long the check may take, in milliseconds. Past it, the file passes unchecked. */
  timeoutMs?: number;
}

// Each format the pipeline reads has a check of its own, in fileValidation/. A format added to the
// map above does not compile until it has one here.
const CONTENT_CHECKS: Record<
  PipelineExtension,
  (file: File, limits: Required<UploadLimits>, signal?: AbortSignal) => Promise<ContentCheck>
> = {
  ".pdf": (file, limits, signal) => checkPdf(file, limits.maxPdfPages, signal),
  ".docx": (file, limits, signal) => checkDocx(file, limits.maxUnzippedSize, signal),
  ".doc": file => checkDoc(file),
};

function isPipelineExtension(extension: string | undefined): extension is PipelineExtension {
  return extension !== undefined && Object.hasOwn(MIME_TYPE_BY_EXTENSION, extension);
}

// The lowercase extension including the dot, or undefined when the name carries none.
export function getFileExtension(fileName: string): string | undefined {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0 || dot === fileName.length - 1) {
    return undefined;
  }
  return fileName.slice(dot).toLowerCase();
}

// The file's type as the server reads it (AttachDocumentHandler#getMimeType): lower case and
// without parameters. The extension names it when the browser sent no type or only the generic
// one. It is undefined if the browser sent no type and the extension is not one of the pipeline's.
function getMimeType(file: File, extension: string | undefined): string | undefined {
  const declared = file.type.split(";", 1)[0].trim().toLowerCase();
  if (declared !== "" && declared !== GENERIC_TYPE) {
    return declared;
  }
  const fromName = isPipelineExtension(extension) ? MIME_TYPE_BY_EXTENSION[extension] : undefined;
  return fromName ?? (declared === "" ? undefined : declared);
}

// The refusal of a file of the wrong type. A known MIME type reads as its extension, such as
// ".docx".
function describeWrongType(file: File, accepted: string[]): string {
  const names = new Set(accepted.map(type => EXTENSIONS.get(type.toLowerCase()) ?? type.toLowerCase()));
  return `${file.name} is not a file of an accepted type: ${[ ...names ].join(", ")}.`;
}

function getLimits({
  maxFileSize = MAX_FILE_SIZE,
  maxPdfPages = MAX_PDF_PAGES,
  maxUnzippedSize = MAX_UNZIPPED_SIZE,
}: UploadLimits): Required<UploadLimits> {
  return { maxFileSize, maxPdfPages, maxUnzippedSize };
}

async function checkUpload(
  file: File,
  accepted: string[],
  limits: Required<UploadLimits>,
  signal: AbortSignal,
): Promise<string | undefined> {
  if (file.size === 0) {
    return `${file.name} is empty.`;
  }
  if (file.size > limits.maxFileSize) {
    const size = formatSize(file.size, Math.ceil);
    return `${file.name} is ${size}, and the limit is ${formatSize(limits.maxFileSize, Math.floor)}.`;
  }
  const extension = getFileExtension(file.name);
  const type = getMimeType(file, extension);
  if (accepted.length > 0 && !accepted.some(entry => entry.toLowerCase() === type)) {
    return describeWrongType(file, accepted);
  }
  if (!isPipelineExtension(extension)) {
    return undefined;
  }
  const content = await CONTENT_CHECKS[extension](file, limits, signal);
  return content.valid ? undefined : `${file.name}: ${content.error}`;
}

/**
 * Checks a file a person picked before it is uploaded: its size, its type and its content. Rejects
 * with the signal's reason if `signal` stops the check.
 *
 * @param file the file the person picked
 * @param options the accepted types, limits, signal and time limit, each optional
 * @returns why the file is refused, for the person to read and starting with the file name;
 *   undefined if it passes, or if the time limit runs out first
 */
export async function validateUpload(file: File, options: UploadCheckOptions = {}): Promise<string | undefined> {
  const { accepted = [], limits = {}, signal, timeoutMs } = options;
  signal?.throwIfAborted();
  const timeout = timeoutMs === undefined ? undefined : AbortSignal.timeout(timeoutMs);
  const stop = AbortSignal.any([ signal, timeout ].filter(each => each !== undefined));
  try {
    return await stopOnAbort(checkUpload(file, accepted, getLimits(limits), stop), stop);
  } catch (error) {
    // When time runs out, the file passes unchecked
    if (timeout?.aborted === true && signal?.aborted !== true) {
      return undefined;
    }
    throw error;
  }
}
