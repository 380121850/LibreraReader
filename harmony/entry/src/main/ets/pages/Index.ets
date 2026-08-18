import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import picker from '@ohos.file.picker';
import {
  version, openDocument, pageCount, renderPage, renderPageAsync,
  getText, searchText, getDocumentInfo, getToc, getPageSize, layoutDocument, closeDocument,
  getAnnotations, addHighlight, deleteAnnotation, saveDocument, isReflowable,
  RenderResult, TextRect, DocumentInfo, TocEntry, RenderOptions, CropRect, AnnotationInfo
} from 'libmupdf_napi.so';
import {
  saveRecentBook, getRecentBooks, getRecentBooksStarred, toggleStar,
  exportRecentBooks, replaceRecentBooks,
  RecentBook
} from '../model/ReadingProgress';
import { exportBookmarks, replaceBookmarks, Bookmark } from '../model/Bookmarks';
import {
  loadSettings, saveSettings, defaultSettings, THEME_NAMES, ReaderSettings
} from '../model/Settings';
import { importUriToSandbox } from '../model/FileImport';
import { KEY_PENDING_URI } from '../entryability/EntryAbility';

/** Backup payload shape (sprint E). */
interface BackupFile {
  app: string;
  version: number;
  exportedAt: string;
  settings: ReaderSettings;
  recents: RecentBook[];
  bookmarks: Bookmark[];
}

/**
 * Library page: multi-tab shell (Recent / Favorites / Settings).
 * Recent and Favorites show cover thumbnails, progress, and a star toggle.
 * The Settings tab hosts the reader settings + backup/restore, followed by
 * the NAPI self-test output.
 */
@Entry
@Component
struct Index {
  @State currentTab: number = 0;
  @State title: string = 'Librera Reader';
  @State status: string = 'initializing...';
  @State recentBooks: RecentBook[] = [];
  @State starredBooks: RecentBook[] = [];

  // ---- Settings (sprint E) ----
  @State settings: ReaderSettings = defaultSettings();
  @State backupText: string = '';

  // ---- NAPI self-test states (Settings tab) ----
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
  @State cropText: string = '';
  @State layoutText: string = '';
  @State fmtText: string = '';
  @State currentPage: number = 0;

  aboutToAppear(): void {
    this.runApiTest();
    this.loadLibrary();
    this.ensureDemoBook();
    this.consumePendingUri();
    this.initSettings();
  }

  private async initSettings(): Promise<void> {
    const ctx = getContext(this);
    this.settings = await loadSettings(ctx);
    console.info(`[Index] Settings loaded: theme=${this.settings.theme} font=${this.settings.fontSize}`);
  }

  private async saveNow(): Promise<void> {
    await saveSettings(getContext(this), this.settings);
  }

  /** Export settings + recents + bookmarks to a JSON file in the sandbox. */
  private async doBackup(): Promise<void> {
    try {
      const ctx = getContext(this);
      const recents = await exportRecentBooks(ctx);
      const bookmarks = await exportBookmarks(ctx);
      const payload: BackupFile = {
        app: 'librera-harmony',
        version: 1,
        exportedAt: new Date().toISOString(),
        settings: this.settings,
        recents: recents,
        bookmarks: bookmarks
      };
      const target = ctx.filesDir + '/librera_backup.json';
      const f = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, JSON.stringify(payload));
      fs.closeSync(f);
      this.backupText = `已导出 ${recents.length} 本记录 / ${bookmarks.length} 条书签 → ${target}`;
      console.info(`[Index] Backup written: ${target}`);
    } catch (e) {
      this.backupText = '导出失败';
      console.error('[Index] backup failed');
    }
  }

  /** Restore settings + recents + bookmarks from the sandbox backup file. */
  private async doRestore(): Promise<void> {
    try {
      const ctx = getContext(this);
      const target = ctx.filesDir + '/librera_backup.json';
      const content = fs.readTextSync(target);
      const payload = JSON.parse(content) as BackupFile;
      if (payload.app !== 'librera-harmony' || payload.settings === undefined) {
        this.backupText = '备份文件无效';
        return;
      }
      await saveSettings(ctx, payload.settings);
      this.settings = payload.settings;
      await replaceRecentBooks(ctx, payload.recents);
      await replaceBookmarks(ctx, payload.bookmarks);
      await this.loadLibrary();
      this.backupText = `已恢复设置 + ${payload.recents.length} 本记录 + ${payload.bookmarks.length} 条书签`;
      console.info(`[Index] Backup restored from ${target}`);
    } catch (e) {
      this.backupText = '恢复失败（请先导出）';
      console.error('[Index] restore failed');
    }
  }

  /**
   * Seed a demo book (rawfile test.pdf) into the library on first run and
   * exercise the cover-generation pipeline (metadata + thumbnail PNG).
   */
  private async ensureDemoBook(): Promise<void> {
    try {
      const ctx = getContext(this);
      const demoPath = ctx.cacheDir + '/test.pdf';
      let existing: RecentBook | undefined = undefined;
      const list = await getRecentBooks(ctx);
      for (let i = 0; i < list.length; i++) {
        if (list[i].path === demoPath) {
          existing = list[i];
          break;
        }
      }
      if (existing !== undefined) {
        console.info(`[Index] Demo book exists: totalPages=${existing.totalPages} cover='${existing.coverPath}'`);
        if (existing.coverPath.length > 0 && existing.totalPages > 0) {
          return; /* demo book already has a cover */
        }
        await this.generateCover(ctx, existing);
      } else {
        /* extract rawfile into the sandbox and add a library entry */
        const content: Uint8Array = await ctx.resourceManager.getRawFileContent('test.pdf');
        const f = fs.openSync(demoPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, content.buffer);
        fs.closeSync(f);
        const book: RecentBook = {
          path: demoPath,
          title: 'test.pdf (demo)',
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: ''
        };
        await saveRecentBook(ctx, book);
        console.info('[Index] Demo book seeded');
        await this.generateCover(ctx, book);
      }

      /* Also seed a reflowable demo (Alice in Wonderland) so the EPUB
       * pipeline (layout / font size / reflow) is reachable from the UI. */
      const epubPath = ctx.cacheDir + '/test.epub';
      let epubExisting: RecentBook | undefined = undefined;
      const epubList = await getRecentBooks(ctx);
      for (let i = 0; i < epubList.length; i++) {
        if (epubList[i].path === epubPath) {
          epubExisting = epubList[i];
          break;
        }
      }
      if (epubExisting === undefined) {
        const epubContent: Uint8Array = await ctx.resourceManager.getRawFileContent('test.epub');
        const ef = fs.openSync(epubPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(ef.fd, epubContent.buffer);
        fs.closeSync(ef);
        const epubBook: RecentBook = {
          path: epubPath,
          title: 'Alice (demo EPUB)',
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: 'Lewis Carroll'
        };
        await saveRecentBook(ctx, epubBook);
        console.info('[Index] Demo EPUB seeded');
        await this.generateCover(ctx, epubBook);
      } else if (epubExisting.coverPath.length === 0 || epubExisting.totalPages === 0) {
        await this.generateCover(ctx, epubExisting);
      }

      /* Format demos (sprint F): TXT / HTML (reflowable) + CBZ (image comic) */
      await this.seedFormatDemo(ctx, 'demo.txt', 'demo.txt (TXT)', '');
      await this.seedFormatDemo(ctx, 'demo.html', 'demo.html (HTML)', '');
      await this.seedFormatDemo(ctx, 'demo.cbz', 'demo.cbz (CBZ)', '');
      await this.loadLibrary();
    } catch (e) {
      console.error('[Index] ensureDemoBook failed');
    }
  }

  /** Seed a rawfile demo book into the library, generating its cover once. */
  private async seedFormatDemo(ctx: Context, rawName: string, title: string, author: string): Promise<void> {
    try {
      const path = ctx.cacheDir + '/' + rawName;
      const list = await getRecentBooks(ctx);
      let existing: RecentBook | undefined = undefined;
      for (let i = 0; i < list.length; i++) {
        if (list[i].path === path) {
          existing = list[i];
          break;
        }
      }
      if (existing === undefined) {
        const content: Uint8Array = await ctx.resourceManager.getRawFileContent(rawName);
        const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, content.buffer);
        fs.closeSync(f);
        const book: RecentBook = {
          path: path,
          title: title,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: author
        };
        await saveRecentBook(ctx, book);
        console.info(`[Index] Demo seeded: ${title}`);
        await this.generateCover(ctx, book);
      } else if (existing.coverPath.length === 0 || existing.totalPages === 0) {
        await this.generateCover(ctx, existing);
      }
    } catch (e) {
      console.error(`[Index] seedFormatDemo failed: ${rawName}`);
    }
  }

  private async loadLibrary(): Promise<void> {
    try {
      const ctx = getContext(this);
      this.recentBooks = await getRecentBooks(ctx);
      this.starredBooks = await getRecentBooksStarred(ctx);
      console.info(`[Index] Library loaded: ${this.recentBooks.length} recent, ${this.starredBooks.length} starred`);
    } catch (e) {
      console.error('[Index] loadLibrary failed');
    }
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
        lastRead: new Date().toISOString(),
        star: false,
        coverPath: '',
        author: ''
      };
      saveRecentBook(ctx, book);
      this.generateCover(ctx, book);
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

  /**
   * Generate a cover thumbnail PNG for a book: open the doc, read metadata,
   * render page 0 at low zoom, encode to PNG in cacheDir/covers/.
   * Also backfills author + totalPages into the persisted record (B2).
   */
  private async generateCover(ctx: Context, book: RecentBook): Promise<void> {
    let handle: ESObject = undefined;
    try {
      handle = openDocument(book.path);
      const pages: number = pageCount(handle);
      book.totalPages = pages;

      /* B2: extract author metadata */
      try {
        const info: DocumentInfo = getDocumentInfo(handle);
        if (info.author !== undefined && info.author !== null && info.author.length > 0) {
          book.author = info.author;
        }
      } catch (e) {
        /* metadata optional */
      }

      /* render page 0 at low zoom for the cover */
      const coverOpts: RenderOptions = { zoom: 0.18 };
      const res: RenderResult = await renderPageAsync(handle, 0, coverOpts);
      const opts: image.InitializationOptions = {
        size: { width: res.width, height: res.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      const pm: image.PixelMap = await image.createPixelMap(res.data, opts);

      /* encode to PNG in cacheDir/covers/ */
      const coversDir = ctx.cacheDir + '/covers';
      try {
        fs.mkdirSync(coversDir);
      } catch (e) {
        /* already exists (2nd+ cover) — fine */
      }
      const safe = book.path.replace(/[\/\\:]/g, '_').substring(0, 60);
      const coverFile = coversDir + '/' + safe + '.png';
      const f = fs.openSync(coverFile, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      const packer: image.ImagePacker = image.createImagePacker();
      const pOpts: image.PackingOption = { format: 'image/png', quality: 80 };
      await packer.packToFile(pm, f.fd, pOpts);
      packer.release();
      fs.closeSync(f);
      book.coverPath = coverFile;
      /* persist cover + totalPages + author together */
      await saveRecentBook(ctx, book);
      console.info(`[Index] Cover generated: ${coverFile} (${res.width}x${res.height}), author='${book.author}'`);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : JSON.stringify(e);
      console.error(`[Index] Cover generation failed for ${book.title}: ${msg}`);
    } finally {
      if (handle !== undefined) {
        try { closeDocument(handle); } catch (_e) {}
      }
    }
  }

  private async openFilePicker(): Promise<void> {
    try {
      const ctx = getContext(this);
      let documentPicker = new picker.DocumentViewPicker(ctx);
      let options: picker.DocumentSelectOptions = {
        fileSuffixFilters: ['PDF|pdf', 'EPUB|epub', 'TXT|txt', 'HTML|html', 'CBZ|cbz', 'MOBI|mobi', 'XPS|xps'],
        maxSelectNumber: 20,
      };

      const uris: Array<string> = await documentPicker.select(options);
      if (uris.length === 0) {
        console.info('[Index] Picker cancelled');
        return;
      }
      console.info(`[Index] Picked ${uris.length} file(s)`);

      /* import each, save to library, generate cover, then open the first */
      let firstPath = '';
      let firstTitle = '';
      for (let i = 0; i < uris.length; i++) {
        const target = importUriToSandbox(ctx, uris[i]);
        const lastSlash: number = target.lastIndexOf('/');
        const fileName = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
        const book: RecentBook = {
          path: target,
          title: fileName,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: ''
        };
        await saveRecentBook(ctx, book);
        this.generateCover(ctx, book);
        if (i === 0) {
          firstPath = target;
          firstTitle = fileName;
        }
      }
      await this.loadLibrary();

      /* Navigate to Reader with the first picked document */
      router.pushUrl({ url: 'components/Reader', params: { docPath: firstPath, title: firstTitle } })
        .then(() => {
          console.info(`[Index] Navigated to Reader (picked ${uris.length} file(s))`);
        })
        .catch((err: Error) => {
          console.error('[Index] Navigate failed: ' + JSON.stringify(err));
        });
    } catch (e) {
      console.error('[Index] Picker error: ' + JSON.stringify(e));
    }
  }

  private async onToggleStar(book: RecentBook): Promise<void> {
    try {
      const ctx = getContext(this);
      const newStar = await toggleStar(ctx, book.path);
      book.star = newStar;
      await this.loadLibrary();
      console.info(`[Index] Star ${book.title} -> ${newStar}`);
    } catch (e) {
      console.error('[Index] toggleStar failed');
    }
  }

  private openBook(book: RecentBook): void {
    router.pushUrl({ url: 'components/Reader', params: { docPath: book.path, title: book.title } })
      .then(() => {
        console.info(`[Index] Opened book: ${book.title}`);
      })
      .catch((err: Error) => {
        console.error('[Index] Navigate to book failed');
      });
  }

  /** Render a single library list row with cover, meta and star. */
  @Builder
  BookRow(book: RecentBook) {
    Row() {
      /* cover thumbnail */
      if (book.coverPath.length > 0) {
        Image(book.coverPath)
          .width(52).height(70)
          .objectFit(ImageFit.Cover)
          .borderRadius(4)
          .backgroundColor('#eee')
      } else {
        Column() {
          Text('📄')
            .fontSize(26)
        }
        .width(52).height(70)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#f0f0f0')
        .borderRadius(4)
      }

      Column({ space: 2 }) {
        Text(book.title.length > 22 ? book.title.substring(0, 22) + '…' : book.title)
          .fontSize(13)
          .fontColor('#333')
          .maxLines(1)
        if (book.author.length > 0) {
          Text(book.author)
            .fontSize(11)
            .fontColor('#888')
            .maxLines(1)
        }
        Text(book.totalPages > 0 ? `p${book.page + 1}/${book.totalPages}` : '')
          .fontSize(11)
          .fontColor(Color.Gray)
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 8 })

      /* star toggle */
      Text(book.star ? '★' : '☆')
        .fontSize(22)
        .fontColor(book.star ? '#f5a623' : '#bbb')
        .margin({ right: 6 })
        .onClick(() => {
          this.onToggleStar(book);
        })
    }
    .width('100%')
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .onClick(() => {
      this.openBook(book);
    })
  }

  build() {
    Column() {
      Text(this.title)
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 8, bottom: 8 })

      // Action buttons
      Row({ space: 12 }) {
        Button('打开文件')
          .fontSize(14)
          .fontColor(Color.White)
          .backgroundColor('#2a7')
          .layoutWeight(1)
          .height(38)
          .onClick(() => {
            this.openFilePicker();
          })

        Button('测试 PDF (Reader)')
          .fontSize(14)
          .fontColor(Color.White)
          .backgroundColor(Color.Blue)
          .layoutWeight(1)
          .height(38)
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
      .width('94%')

      // Main tabs
      Tabs({ barPosition: BarPosition.Start, index: this.currentTab }) {
        TabContent() {
          this.buildRecentTab()
        }
        .tabBar('最近阅读')

        TabContent() {
          this.buildStarredTab()
        }
        .tabBar('收藏')

        TabContent() {
          this.buildSettingsTab()
        }
        .tabBar('设置')
      }
      .width('100%')
      .layoutWeight(1)
      .onChange((index: number) => {
        this.currentTab = index;
        console.info(`[Index] Tab switched to ${index}`);
      })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildRecentTab() {
    Scroll() {
      Column({ space: 8 }) {
        if (this.recentBooks.length === 0) {
          Text('还没有阅读记录，点上方"打开文件"导入书籍')
            .fontSize(13).fontColor('#999')
            .margin({ top: 40 })
        } else {
          ForEach(this.recentBooks, (book: RecentBook) => {
            Column() {
              this.BookRow(book)
              Divider().color('#eee')
            }
          }, (item: RecentBook) => item.path)
        }
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8 })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildStarredTab() {
    Scroll() {
      Column({ space: 8 }) {
        if (this.starredBooks.length === 0) {
          Text('还没有收藏，点列表右侧 ☆ 收藏书籍')
            .fontSize(13).fontColor('#999')
            .margin({ top: 40 })
        } else {
          ForEach(this.starredBooks, (book: RecentBook) => {
            Column() {
              this.BookRow(book)
              Divider().color('#eee')
            }
          }, (item: RecentBook) => item.path)
        }
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8 })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildSettingsTab() {
    Scroll() {
      Column({ space: 8 }) {
        /* ===== Reader settings (sprint E) ===== */
        Text('阅读设置')
          .fontSize(15).fontWeight(FontWeight.Bold)
          .margin({ top: 4 })

        // Theme
        Row({ space: 8 }) {
          Text('主题:')
            .fontSize(13).fontColor('#555')
          ForEach(THEME_NAMES, (name: string, idx: number) => {
            Button(name)
              .fontSize(12).height(32)
              .backgroundColor(this.settings.theme === idx ? '#2a7' : '#e0e0e0')
              .fontColor(this.settings.theme === idx ? Color.White : '#333')
              .onClick(() => {
                this.settings.theme = idx;
                this.saveNow();
              })
          }, (name: string, idx: number) => `theme_${idx}`)
        }

        // Default zoom
        Row({ space: 8 }) {
          Text('默认缩放:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.zoom, min: 0.5, max: 4, step: 0.25 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.zoom = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${Math.round(this.settings.zoom * 100)}%`)
            .fontSize(11).fontColor('#777').width(40)
        }

        // Scroll mode & page mode
        Row({ space: 8 }) {
          Text('翻页:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.scrollMode === 0 ? '水平翻页' : '垂直滚动')
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.scrollMode = this.settings.scrollMode === 0 ? 1 : 0;
              this.saveNow();
            })
          Button(this.settings.pageMode === 0 ? '单页' : (this.settings.pageMode === 1 ? '双页' : '乐谱'))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.pageMode = (this.settings.pageMode + 1) % 3;
              this.saveNow();
            })
        }

        // Brightness
        Row({ space: 8 }) {
          Text('亮度:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.brightness, min: 1, max: 255, step: 4 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.brightness = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${Math.round(this.settings.brightness / 2.55)}%`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // Auto flip
        Row({ space: 8 }) {
          Text('自动翻页:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.autoFlip ? '开' : '关')
            .fontSize(12).height(32)
            .backgroundColor(this.settings.autoFlip ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.autoFlip ? Color.White : '#333')
            .onClick(() => {
              this.settings.autoFlip = !this.settings.autoFlip;
              this.saveNow();
            })
          Text(`${this.settings.autoFlipInterval}s`)
            .fontSize(11).fontColor('#777')
            .onClick(() => {
              this.settings.autoFlipInterval = this.settings.autoFlipInterval >= 5 ? 1 : this.settings.autoFlipInterval + 1;
              this.saveNow();
            })
        }

        // TTS rate / pitch / RSVP speed
        Row({ space: 8 }) {
          Text('TTS语速:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.ttsRate, min: 0.5, max: 2.0, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.ttsRate = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.ttsRate.toFixed(1)}x`)
            .fontSize(11).fontColor('#777').width(36)
        }

        Row({ space: 8 }) {
          Text('TTS音调:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.ttsPitch, min: 0.5, max: 2.0, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.ttsPitch = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.ttsPitch.toFixed(1)}x`)
            .fontSize(11).fontColor('#777').width(36)
        }

        Row({ space: 8 }) {
          Text('速读:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.rsvpWpm, min: 100, max: 800, step: 50 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.rsvpWpm = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.rsvpWpm}`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // Reflowable typography
        Text('重排文档（EPUB/HTML）排版:')
          .fontSize(13).fontColor('#555')
          .margin({ top: 4 })

        Row({ space: 8 }) {
          Text('字号:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.fontSize, min: 10, max: 28, step: 1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.fontSize = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.fontSize}`)
            .fontSize(11).fontColor('#777').width(30)
        }

        Row({ space: 8 }) {
          Text('行距:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.lineHeight, min: 1.0, max: 2.5, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.lineHeight = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.lineHeight.toFixed(1)}`)
            .fontSize(11).fontColor('#777').width(30)
        }

        Row({ space: 8 }) {
          Text('页边距:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.margin, min: 0, max: 80, step: 4 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.margin = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.margin}pt`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // Backup / restore
        Row({ space: 8 }) {
          Button('导出备份')
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#2a7').fontColor(Color.White)
            .onClick(() => this.doBackup())
          Button('恢复备份')
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#ff9800').fontColor(Color.White)
            .onClick(() => this.doRestore())
        }

        if (this.backupText.length > 0) {
          Text(this.backupText)
            .fontSize(11).fontColor('#888').textAlign(TextAlign.Center).width('100%')
        }

        Divider().color('#ddd')

        /* ===== NAPI self-test (kept for diagnostics) ===== */
        Text(this.status)
          .fontSize(12)
          .fontColor(Color.Gray)
          .textAlign(TextAlign.Center)
          .width('94%')

        Text(this.pageInfo)
          .fontSize(12)
          .fontColor(Color.Blue)

        if (this.pixelMap) {
          Image(this.pixelMap)
            .objectFit(ImageFit.Contain)
            .width('70%')
            .borderRadius(6)
        }

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
        .width('94%')

        Text(this.docInfo)
          .fontSize(10)
          .fontColor(Color.Green)

        Column({ space: 4 }) {
          Text(this.tocText).fontSize(11).fontColor('#555')
          Text(this.pageSizeText).fontSize(11).fontColor('#555')
          Text(this.asyncRenderText).fontSize(11).fontColor('#2a7')
          Text(this.rotatedText).fontSize(11).fontColor('#2a7')
          Text(this.invertedText).fontSize(11).fontColor('#2a7')
          Text(this.epubText).fontSize(11).fontColor('#b60')
          Text(this.cropText).fontSize(11).fontColor('#2a7')
          Text(this.layoutText).fontSize(11).fontColor('#2a7')
          Text(this.fmtText).fontSize(11).fontColor('#b60')
        }
        .width('94%')
        .padding(8)
        .backgroundColor('#f0f4ff')
        .borderRadius(8)
      }
      .width('100%')
      .padding({ top: 8, bottom: 16 })
    }
    .width('100%')
    .height('100%')
  }

  // ---- NAPI self-test (runs once on launch, results shown in Settings tab) ----
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

      // 10. renderPageAsync with crop
      try {
        const cropRect: CropRect = { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.9 };
        const cOpts: RenderOptions = { zoom: 1.0, crop: cropRect };
        const cRes: RenderResult = await renderPageAsync(handle, 0, cOpts);
        this.cropText = `crop 10% OK: ${cRes.width}x${cRes.height}`;
        console.info(`[Index] renderPageAsync crop OK: ${cRes.width}x${cRes.height}`);
      } catch (e) {
        this.cropText = 'crop: error';
        console.error('[Index] renderPageAsync crop FAILED');
      }

      // 11. layoutDocument
      try {
        layoutDocument(handle, 595, 842, 12);
        this.layoutText = 'layout OK (reflow target 595x842 em=12)';
        console.info('[Index] layoutDocument OK (no-op on PDF)');
      } catch (e) {
        this.layoutText = 'layout: error';
        console.error('[Index] layoutDocument FAILED');
      }

      // 12. closeDocument
      try {
        closeDocument(handle);
        console.info('[Index] closeDocument OK');
        handle = undefined;
      } catch (e) {
        console.error('[Index] closeDocument FAILED');
      }

      // 13. EPUB open + render
      try {
        const epubTarget = ctx.cacheDir + '/test.epub';
        const epubContent: Uint8Array = await ctx.resourceManager.getRawFileContent('test.epub');
        const epubFile = fs.openSync(epubTarget, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(epubFile.fd, epubContent.buffer);
        fs.closeSync(epubFile);

        const epubHandle: ESObject = openDocument(epubTarget);
        const epubPages: number = pageCount(epubHandle);
        const epubRes: RenderResult = renderPage(epubHandle, 0, 1.5);
        this.epubText = `EPUB OK: ${epubPages} pages, page0=${epubRes.width}x${epubRes.height}`;
        console.info(`[Index] EPUB open+render OK: ${epubPages} pages`);

        try {
          const epubToc: TocEntry[] = getToc(epubHandle);
          if (epubToc.length > 0) {
            this.epubText += ` | TOC=${epubToc.length} entries`;
            console.info(`[Index] EPUB getToc OK: ${epubToc.length} entries, first='${epubToc[0].title}'`);
          }
        } catch (_e) {
          console.info('[Index] EPUB getToc: no outline');
        }

        closeDocument(epubHandle);
      } catch (e) {
        this.epubText = 'EPUB: error - ' + JSON.stringify(e);
        console.error('[Index] EPUB FAILED: ' + JSON.stringify(e));
      }

      // 14. Annotations round-trip (PDF only; re-open since handle was closed)
      let annHandle: ESObject = undefined;
      try {
        annHandle = openDocument(target);
        addHighlight(annHandle, 0, 0.2, 0.3, 0.8, 0.5, '#ffff00');
        console.info('[Index] addHighlight OK');

        const anns: AnnotationInfo[] = getAnnotations(annHandle, 0);
        console.info(`[Index] getAnnotations OK: ${anns.length} on page 1 (type=${anns.length > 0 ? anns[0].type : 'none'})`);

        if (anns.length > 0) {
          deleteAnnotation(annHandle, 0, 0);
          const after: AnnotationInfo[] = getAnnotations(annHandle, 0);
          console.info(`[Index] deleteAnnotation OK: now ${after.length}`);
        }

        saveDocument(annHandle, target);
        console.info('[Index] saveDocument OK (annotations persisted)');
      } catch (e) {
        const msg: string = e instanceof Error ? e.message : JSON.stringify(e);
        console.error('[Index] Annotation test FAILED: ' + msg);
      } finally {
        if (annHandle !== undefined) {
          try { closeDocument(annHandle); } catch (_e) {}
        }
      }

      // 15. Format sweep (sprint F): TXT / HTML / CBZ
      try {
        const formats: string[] = ['demo.txt', 'demo.html', 'demo.cbz'];
        let sweep = '';
        for (let i = 0; i < formats.length; i++) {
          try {
            const name = formats[i];
            const fmtTarget = ctx.cacheDir + '/' + name;
            const content: Uint8Array = await ctx.resourceManager.getRawFileContent(name);
            const f = fs.openSync(fmtTarget, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
            fs.writeSync(f.fd, content.buffer);
            fs.closeSync(f);
            const h: ESObject = openDocument(fmtTarget);
            const n: number = pageCount(h);
            let reflowable = false;
            try {
              reflowable = isReflowable(h);
            } catch (e) {
              /* not supported → fixed layout */
            }
            const r: RenderResult = renderPage(h, 0, 1.0);
            let chars = -1;
            if (reflowable) {
              try {
                chars = getText(h, 0, 1.0).length;
              } catch (e) {
                /* no text layer */
              }
            }
            sweep += `${name}:${n}p${reflowable ? '/reflow' : '/fixed'}/${r.width}x${r.height}` +
              (chars >= 0 ? `/${chars}ch` : '') + '  ';
            console.info(`[Index] FMT ${name} OK: ${n} pages reflow=${reflowable} render=${r.width}x${r.height} text=${chars}`);
            closeDocument(h);
          } catch (e) {
            sweep += `${formats[i]}:ERR  `;
            console.error(`[Index] FMT ${formats[i]} FAILED`);
          }
        }
        this.fmtText = sweep;
      } catch (e) {
        this.fmtText = 'format sweep failed';
      }

      this.status = `mupdf ${mupdfVersion} | pages=${pages} | ${res.width}x${res.height}`;
    } catch (e) {
      this.status = 'error: ' + JSON.stringify(e);
      if (handle !== undefined) {
        try { closeDocument(handle); } catch (_e) {}
      }
    }
  }
}
