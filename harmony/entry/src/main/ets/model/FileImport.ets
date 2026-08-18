import fs from '@ohos.file.fs';

/**
 * Shared helper: import a document URI (from DocumentViewPicker or an
 * "open with" file-association want) into the app sandbox cache so it can be
 * opened by path with openDocument(), independent of temporary URI grants.
 *
 * Returns the sandbox path, or '' on failure.
 */
export function importUriToSandbox(ctx: Context, uri: string): string {
  let fileName = 'document.pdf';
  const lastSlash: number = uri.lastIndexOf('/');
  if (lastSlash >= 0 && lastSlash < uri.length - 1) {
    fileName = uri.substring(lastSlash + 1);
  }
  /* strip query/fragment if any crept in */
  const q: number = fileName.indexOf('?');
  if (q > 0) {
    fileName = fileName.substring(0, q);
  }

  const target = ctx.cacheDir + '/' + fileName;
  const file: fs.File = fs.openSync(uri, fs.OpenMode.READ_ONLY);
  try {
    const stat: fs.Stat = fs.statSync(file.fd);
    const buf: ArrayBuffer = new ArrayBuffer(stat.size);
    fs.readSync(file.fd, buf);
    const outFile: fs.File = fs.openSync(target,
      fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(outFile.fd, buf);
    } finally {
      fs.closeSync(outFile);
    }
    console.info(`[FileImport] ${fileName} -> sandbox, size=${stat.size}`);
    return target;
  } finally {
    fs.closeSync(file);
  }
}
