/**
 * MuPDF NAPI bindings (libmupdf_napi.so).
 */
export interface RenderResult {
  width: number;
  height: number;
  /** RGBA_8888 pixels, width * height * 4 bytes */
  data: ArrayBuffer;
}

export interface TextRect {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface DocumentInfo {
  title?: string;
  author?: string;
  subject?: string;
  creator?: string;
  producer?: string;
  creationDate?: string;
  modDate?: string;
}

export interface TocEntry {
  title: string;
  /** target page, -1 if the link is not page-based */
  page: number;
  depth: number;
}

/**
 * Render options for renderPageAsync.
 * rotationDeg must be a multiple of 90 (0/90/180/270).
 * invert flips R/G/B channels after rendering.
 */
export interface RenderOptions {
  zoom: number;
  rotationDeg?: number;
  invert?: boolean;
}

export interface MupdfDocument {
  /** opaque native handle; only pass back into this module */
  handle: ESObject;
}

export function version(): string;
export function openDocument(path: string): ESObject;
/** Open document via file descriptor (from @ohos.file.fs.openSync) */
export function openDocumentByFd(fd: number): ESObject;
export function pageCount(handle: ESObject): number;
export function renderPage(handle: ESObject, pageNumber: number, zoom: number): RenderResult;
/** Async render: returns Promise<RenderResult>, renders on worker thread with pthread-locked fz_context */
export function renderPageAsync(handle: ESObject, pageNumber: number, options: RenderOptions): Promise<RenderResult>;
/** Get table of contents (outline) as a flat list with depth markers */
export function getToc(handle: ESObject): TocEntry[];
/** Native media size in points at 0 degrees rotation */
export function getPageSize(handle: ESObject, pageNumber: number): TextRect;
/** Extract text content from a page */
export function getText(handle: ESObject, pageNumber: number, zoom: number): string;
/** Search text and return bounding rectangles */
export function searchText(handle: ESObject, text: string, pageNumber: number): TextRect[];
/** Get document metadata */
export function getDocumentInfo(handle: ESObject): DocumentInfo;
export function closeDocument(handle: ESObject): void;
