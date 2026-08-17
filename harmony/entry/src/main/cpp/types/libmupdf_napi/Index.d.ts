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
/** Extract text content from a page */
export function getText(handle: ESObject, pageNumber: number, zoom: number): string;
/** Search text and return bounding rectangles */
export function searchText(handle: ESObject, text: string, pageNumber: number): TextRect[];
/** Get document metadata */
export function getDocumentInfo(handle: ESObject): DocumentInfo;
export function closeDocument(handle: ESObject): void;
