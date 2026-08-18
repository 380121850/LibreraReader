import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import picker from '@ohos.file.picker';
import {
  version, openDocument, pageCount, renderPage, renderPageAsync,
  getText, searchText, getDocumentInfo, getToc, getPageSize, closeDocument,
  RenderResult, TextRect, DocumentInfo, TocEntry, RenderOptions
} from 'libmupdf_napi.so';
import { saveRecentBook, getRecentBooks, RecentBook } from '../model/ReadingProgress';
import { importUriToSandbox } from '../model/FileImport';
import { KEY_PENDING_URI } from '../entryability/EntryAbility';

@Entry
@Component
struct Index {
  @State title: string = 'Librera Reader';
  @State status: string = 'initializing...';
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State pageInfo: string = '';
  @State textResult: string = '';
  @State docInfo: string = '';
  @State tocText: string = '';
  @State pageSizeText: string = '';
  @State asyncRenderText: string = '';
  @State rotatedText: string = '';
  @State invertedText: string = '';
  @State epubText: string = '';
  @State recentBooks: RecentBook[] = [];
  @State currentPage: number = 0;

  aboutToAppear(): void {
    this.runApiTest();
    this.loadRecentBooks();
    this.consumePendingUri();
  }

  /**
   * Handle "open with" file-association launches: EntryAbility stashed the
   * want URI in AppStorage; import it and jump straight to the Reader.
   */
  private consumePendingUri(): void {
    const uri: string | undefined = AppStorage.get<string>(KEY_PENDING_URI);
    if (uri === undefined || uri.length === 0) {
      return;
    }
    AppStorage.setOrCreate(KEY_PENDING_URI, '');
    console.info(`[Index] Pending open-with URI: ${uri}`);
    try {
      const ctx = getContext(this);
      const target = importUriToSandbox(ctx, uri);
      const lastSlash: number = target.lastIndexOf('/');
      const fileName = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
      const book: RecentBook = {
        path: target,
        title: fileName,
        page: 0,
        totalPages: 0,
        lastRead: new Date().toISOString()
      };
      saveRecentBook(ctx, book);
      router.pushUrl({ url: 'components/Reader', params: { docPath: target, title: fileName } })
        .then(() => {
          console.info(`[Index] Navigated to Reader (open-with: ${fileName})`);
        })
        .catch((err: Error) => {
          console.error('[Index] Navigate failed: ' + JSON.stringify(err));
        });
    } catch (e) {
      console.error('[Index] Open-with import failed: ' + JSON.stringify(e));
    }
  }

  private async loadRecentBooks(): Promise<void> {
    try {
      const ctx = getContext(this);
      this.recentBooks = await getRecentBooks(ctx);
      console.info(`[Index] Loaded ${this.recentBooks.length} recent books`);
    } catch (e) {
      console.error('[Index] loadRecentBooks failed');
    }
  }

  private async openFilePicker(): Promise<void> {
    try {
      const ctx = getContext(this);
      let documentPicker = new picker.DocumentViewPicker(ctx);
      let options: picker.DocumentSelectOptions = {
        fileSuffixFilters: ['PDF|pdf'],
        maxSelectNumber: 1,
      };

      const uris: Array<string> = await documentPicker.select(options);
      if (uris.length === 0) {
        console.info('[Index] Picker cancelled');
        return;
      }

      const uri: string = uris[0];
      console.info(`[Index] Picked: ${uri}`);

      /* Copy to sandbox cache so it persists across picker grant expiry */
      const target = importUriToSandbox(ctx, uri);
      const lastSlash: number = target.lastIndexOf('/');
      const fileName = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;

      /* Save to recent books */
      const book: RecentBook = {
        path: target,
        title: fileName,
        page: 0,
        totalPages: 0,
        lastRead: new Date().toISOString()
      };
      await saveRecentBook(ctx, book);

      /* Navigate to Reader with the document path */
      router.pushUrl({ url: 'components/Reader', params: { docPath: target, title: fileName } })
        .then(() => {
          console.info('[Index] Navigated to Reader (picked file)');
        })
        .catch((err: Error) => {
          console.error('[Index] Navigate failed: ' + JSON.stringify(err));
        });
    } catch (e) {
      console.error('[Index] Picker error: ' + JSON.stringify(e));
    }
  }

  private async runApiTest(): Promise<void> {
    let handle: ESObject = undefined;
    try {
      const ctx = getContext(this);
      const target = ctx.cacheDir + '/test.pdf';

      const content: Uint8Array = await ctx.resourceManager.getRawFileContent('test.pdf');
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content.buffer);
      fs.closeSync(file);

      const mupdfVersion: string = version();
      handle = openDocument(target);
      const pages: number = pageCount(handle);

      // 1. Sync render page 0
      const res: RenderResult = renderPage(handle, 0, 2.0);
      this.currentPage = 0;
      const imgOpts: image.InitializationOptions = {
        size: { width: res.width, height: res.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(res.data, imgOpts);
      console.info(`[Index] sync render OK: ${res.width}x${res.height}`);

      // 2. getText
      try {
        const text: string = getText(handle, 0, 1.0);
        this.textResult = text.length > 500 ? text.substring(0, 500) + '...' : text;
        console.info(`[Index] getText OK: ${text.length} chars`);
      } catch (e) {
        this.textResult = 'text: (N/A)';
        console.error('[Index] getText FAILED');
      }

      // 3. getDocumentInfo
      try {
        const info: DocumentInfo = getDocumentInfo(handle);
        const parts: string[] = [];
        if (info.title) parts.push(`title=${info.title}`);
        if (info.author) parts.push(`author=${info.author}`);
        if (info.creator) parts.push(`creator=${info.creator}`);
        if (info.producer) parts.push(`producer=${info.producer}`);
        this.docInfo = parts.length > 0 ? parts.join(' | ') : 'no metadata';
        console.info(`[Index] getDocumentInfo OK`);
      } catch (e) {
        this.docInfo = 'no metadata';
        console.error('[Index] getDocumentInfo FAILED');
      }

      // 4. searchText
      try {
        const rects: TextRect[] = searchText(handle, 'PDF', 0);
        if (rects.length > 0) {
          this.pageInfo = `found ${rects.length} "PDF" on page 1`;
        } else {
          this.pageInfo = `page 1 | ${rects.length} matches`;
        }
        console.info(`[Index] searchText OK: ${rects.length} hits`);
      } catch (e) {
        this.pageInfo = 'page 1 | search N/A';
        console.error('[Index] searchText FAILED');
      }

      // 5. getToc
      try {
        const toc: TocEntry[] = getToc(handle);
        if (toc.length === 0) {
          this.tocText = 'TOC: (empty)';
          console.info('[Index] getToc OK: 0 entries');
        } else {
          const lines: string[] = [];
          for (let i = 0; i < toc.length && i < 10; i++) {
            lines.push(`  ${' '.repeat(toc[i].depth * 2)}${toc[i].title} [p${toc[i].page}]`);
          }
          this.tocText = `TOC (${toc.length}): ${lines.join(' | ')}`;
          console.info(`[Index] getToc OK: ${toc.length} entries`);
        }
      } catch (e) {
        this.tocText = 'TOC: error';
        console.error('[Index] getToc FAILED');
      }

      // 6. getPageSize
      try {
        const sz: TextRect = getPageSize(handle, 0);
        const w = ((sz.x1 - sz.x0) / 1000).toFixed(1);
        const h = ((sz.y1 - sz.y0) / 1000).toFixed(1);
        this.pageSizeText = `Page size: ${w} x ${h} pt`;
        console.info(`[Index] getPageSize OK: ${w}x${h}`);
      } catch (e) {
        this.pageSizeText = 'Page size: error';
        console.error('[Index] getPageSize FAILED');
      }

      // 7. renderPageAsync
      try {
        const aOpts: RenderOptions = { zoom: 1.5 };
        const aRes: RenderResult = await renderPageAsync(handle, 0, aOpts);
        this.asyncRenderText = `async render OK: ${aRes.width}x${aRes.height}`;
        console.info(`[Index] renderPageAsync OK: ${aRes.width}x${aRes.height}`);
      } catch (e) {
        this.asyncRenderText = 'async render: error';
        console.error('[Index] renderPageAsync FAILED');
      }

      // 8. renderPageAsync with rotation 90
      try {
        const rOpts: RenderOptions = { zoom: 1.0, rotationDeg: 90 };
        const rRes: RenderResult = await renderPageAsync(handle, 0, rOpts);
        this.rotatedText = `rotated 90 OK: ${rRes.width}x${rRes.height}`;
        console.info(`[Index] renderPageAsync rot=90 OK: ${rRes.width}x${rRes.height}`);
      } catch (e) {
        this.rotatedText = 'rotated 90: error';
        console.error('[Index] renderPageAsync rot=90 FAILED');
      }

      // 9. renderPageAsync with invert
      try {
        const iOpts: RenderOptions = { zoom: 1.0, invert: true };
        const iRes: RenderResult = await renderPageAsync(handle, 0, iOpts);
        this.invertedText = `inverted OK: ${iRes.width}x${iRes.height}`;
        console.info(`[Index] renderPageAsync invert OK: ${iRes.width}x${iRes.height}`);
      } catch (e) {
        this.invertedText = 'inverted: error';
        console.error('[Index] renderPageAsync invert FAILED');
      }

      // 10. closeDocument
      try {
        closeDocument(handle);
        console.info('[Index] closeDocument OK');
        handle = undefined;
      } catch (e) {
        console.error('[Index] closeDocument FAILED');
      }

      // 11. EPUB (skipped - MuPDF upstream SIGSEGV)
      this.epubText = 'EPUB: skipped (MuPDF CSS parser bug)';

      this.status = `mupdf ${mupdfVersion} | pages=${pages} | ${res.width}x${res.height}`;
    } catch (e) {
      this.status = 'error: ' + JSON.stringify(e);
      if (handle !== undefined) {
        try { closeDocument(handle); } catch (_e) {}
      }
    }
  }

  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text(this.title)
          .fontSize(28)
          .fontWeight(FontWeight.Bold)

        Text(this.status)
          .fontSize(14)
          .fontColor(Color.Gray)
          .textAlign(TextAlign.Center)
          .padding({ left: 16, right: 16 })

        // Action buttons
        Row({ space: 12 }) {
          Button('打开文件')
            .fontSize(14)
            .fontColor(Color.White)
            .backgroundColor('#2a7')
            .layoutWeight(1)
            .height(40)
            .onClick(() => {
              this.openFilePicker();
            })

          Button('测试 PDF (Reader)')
            .fontSize(14)
            .fontColor(Color.White)
            .backgroundColor(Color.Blue)
            .layoutWeight(1)
            .height(40)
            .onClick(() => {
              router.pushUrl({ url: 'components/Reader' })
                .then(() => {
                  console.info('[Index] Navigated to Reader (test pdf)');
                })
                .catch((err: Error) => {
                  console.error('[Index] Navigate failed: ' + JSON.stringify(err));
                });
            })
        }
        .width('92%')

        // Recent books list
        if (this.recentBooks.length > 0) {
          Text('最近阅读')
            .fontSize(14)
            .fontColor('#555')
            .alignSelf(ItemAlign.Start)
            .margin({ left: 8 })

          Column() {
            ForEach(this.recentBooks, (book: RecentBook) => {
              Row() {
                Text(book.title.length > 20 ? book.title.substring(0, 20) + '…' : book.title)
                  .fontSize(13)
                  .fontColor('#333')
                  .layoutWeight(1)

                Text(`p${book.page + 1}/${book.totalPages}`)
                  .fontSize(11)
                  .fontColor(Color.Gray)
              }
              .width('100%')
              .padding({ left: 8, right: 8, top: 6, bottom: 6 })
              .onClick(() => {
                router.pushUrl({ url: 'components/Reader', params: { docPath: book.path, title: book.title } })
                  .then(() => {
                    console.info(`[Index] Opened recent: ${book.title}`);
                  })
                  .catch((err: Error) => {
                    console.error('[Index] Navigate to recent failed');
                  });
              })

              Divider()
                .color('#eee')
            }, (item: RecentBook, index?: number) => item.path + String(index))
          }
          .width('92%')
          .backgroundColor(Color.White)
          .borderRadius(8)
        }

        // API test results (collapsible - show below if space allows)
        Text(this.pageInfo)
          .fontSize(12)
          .fontColor(Color.Blue)
          .textAlign(TextAlign.Center)

        Column() {
          Text(this.textResult)
            .fontSize(10)
            .fontColor('#666')
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Clip })
            .width('92%')
        }
        .padding(8)
        .backgroundColor(Color.White)
        .borderRadius(8)
        .width('92%')

        Text(this.docInfo)
          .fontSize(10)
          .fontColor(Color.Green)
          .textAlign(TextAlign.Center)

        Column({ space: 4 }) {
          Text(this.tocText).fontSize(11).fontColor('#555')
          Text(this.pageSizeText).fontSize(11).fontColor('#555')
          Text(this.asyncRenderText).fontSize(11).fontColor('#2a7')
          Text(this.rotatedText).fontSize(11).fontColor('#2a7')
          Text(this.invertedText).fontSize(11).fontColor('#2a7')
          Text(this.epubText).fontSize(11).fontColor('#b60')
        }
        .width('92%')
        .padding(8)
        .backgroundColor('#f0f4ff')
        .borderRadius(8)
      }
      .width('100%')
      .padding(16)
    }
    .scrollable(ScrollDirection.Vertical)
    .width('100%')
    .height('100%')
  }
}
