/**
 * Reader component for Librera Reader (HarmonyOS).
 *
 * Features:
 * - Dual scroll modes: horizontal Swiper paging + vertical continuous List scrolling
 * - Pinch-to-zoom gesture (renderPageAsync with new zoom)
 * - Night mode (invertColor via renderPageAsync options)
 * - TOC sidebar (getToc → tap to jump page)
 * - Menu overlay: progress, zoom %, mode toggle, night toggle, TOC button
 * - Tap zones: left=back, right=next, center=toggle menu
 * - Reading progress persistence on every page change
 * - TTS reading (sprint D): Web Speech API via hidden ArkWeb host, sentence by
 *   sentence with rate/pitch control, auto-advances to the next page
 * - Speed reading RSVP (sprint D): CJK-aware word flashes with WPM control
 */

import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import brightness from '@system.brightness';
import webview from '@ohos.web.webview';
import {
  version, openDocument, pageCount, renderPageAsync, closeDocument, getToc, getPageSize,
  searchText, getText, getAnnotations, addHighlight, addInkStroke, deleteAnnotation, saveDocument,
  layoutDocument, isReflowable,
  RenderResult, RenderOptions, TocEntry, CropRect, TextRect, AnnotationInfo
} from 'libmupdf_napi.so';
import { saveRecentBook, getPageForPath, RecentBook } from '../model/ReadingProgress';
import { getBookmarks, addBookmark, removeBookmark, Bookmark } from '../model/Bookmarks';
import { TextUtils } from '../model/TextUtils';
import { loadSettings, saveSettings, THEME_NAMES, ReaderSettings } from '../model/Settings';

const LEFT_ZONE_RATIO: number = 0.3;
const RIGHT_ZONE_RATIO: number = 0.3;
const MIN_ZOOM: number = 0.5;
const MAX_ZOOM: number = 4.0;
const ZOOM_STEP: number = 0.25;
/** Layout page size (points) used for reflowable documents */
const REFLOW_W: number = 595;
const REFLOW_H: number = 842;

/**
 * Data source for page list (shared by Swiper and List modes).
 */
class PageDataSource implements IDataSource {
  private pageIndices: number[] = [];
  private listeners: DataChangeListener[] = [];
  private docHandle: ESObject | undefined = undefined;
  private docPath: string = '';
  private zoom: number = 1.5;
  private invert: boolean = false;

  setZoom(zoom: number): void {
    this.zoom = zoom;
  }

  setInvert(invert: boolean): void {
    this.invert = invert;
  }

  async renderPage(pageNumber: number, cropRect?: CropRect): Promise<RenderResult> {
    if (this.docHandle === undefined) {
      if (this.docPath.length > 0) {
        console.info('[Reader] Document handle lost, re-opening');
        this.openDoc(this.docPath);
      } else {
        throw new Error('Document not opened');
      }
    }
    const opts: RenderOptions = { zoom: this.zoom, invert: this.invert };
    if (cropRect !== undefined) {
      opts.crop = cropRect;
    }
    return await renderPageAsync(this.docHandle, pageNumber, opts);
  }

  getPageCount(): number {
    let count = 0;
    if (this.docHandle !== undefined) {
      count = pageCount(this.docHandle);
    }
    return count;
  }

  getDocHandle(): ESObject | undefined {
    return this.docHandle;
  }

  openDoc(path: string): void {
    try {
      this.docPath = path;
      if (this.docHandle !== undefined) {
        try { closeDocument(this.docHandle); } catch (_e) {}
      }
      this.docHandle = openDocument(path);
      const total: number = pageCount(this.docHandle);
      this.pageIndices = [];
      for (let i = 0; i < total; i++) {
        this.pageIndices.push(i);
      }
      for (let j = 0; j < this.listeners.length; j++) {
        this.listeners[j].onDataReloaded();
      }
    } catch (e) {
      console.error('[Reader] Failed to open document');
      throw e as Error;
    }
  }

  closeDoc(): void {
    if (this.docHandle !== undefined) {
      try {
        closeDocument(this.docHandle);
      } catch (e) {
        console.error('[Reader] Error closing document');
      }
      this.docHandle = undefined;
    }
  }

  totalCount(): number {
    return this.pageIndices.length;
  }

  getData(index: number): string {
    if (index < 0 || index >= this.pageIndices.length) {
      return '0';
    }
    return String(this.pageIndices[index]);
  }

  registerDataChangeListener(listener: DataChangeListener): void {
    if (this.listeners.indexOf(listener) < 0) {
      this.listeners.push(listener);
    }
  }

  unregisterDataChangeListener(listener: DataChangeListener): void {
    const pos = this.listeners.indexOf(listener);
    if (pos >= 0) {
      this.listeners.splice(pos, 1);
    }
  }
}

/**
 * Single page renderer (used in Swiper, List, double-page and musician modes).
 * cropRect (optional) selects a normalized sub-rectangle of the page.
 * searchHits (optional) is a list of normalized (0..1) hit rectangles to
 * highlight; coordinates are relative to the full (uncropped) page.
 */
@Component
struct PageRenderer {
  pageIndex: number = 0;
  dataSource: PageDataSource = new PageDataSource();
  cropRect: CropRect | undefined = undefined;
  searchHits: TextRect[] = [];
  annotations: AnnotationInfo[] = [];
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State isLoading: boolean = true;
  @State errorText: string = '';
  @State renderW: number = 1;
  @State renderH: number = 1;

  aboutToAppear(): void {
    this.loadData();
  }

  async loadData(): Promise<void> {
    if (this.dataSource === undefined) {
      return;
    }
    try {
      this.isLoading = true;
      this.errorText = '';
      const renderResult: RenderResult = await this.dataSource.renderPage(this.pageIndex, this.cropRect);
      const opts: image.InitializationOptions = {
        size: { width: renderResult.width, height: renderResult.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(renderResult.data, opts);
      this.renderW = renderResult.width;
      this.renderH = renderResult.height;
      console.info(`[Reader] Page ${this.pageIndex} rendered OK: ${renderResult.width}x${renderResult.height}`);
    } catch (e) {
      this.errorText = 'Render error';
      const msg: string = e instanceof Error ? e.message : String(e);
      console.error(`[Reader] Page ${this.pageIndex} render failed: ${msg}`);
    } finally {
      this.isLoading = false;
    }
  }

  build() {
    Column() {
      if (this.isLoading) {
        Row() {
          Progress({ value: 30, total: 100 })
            .width(48).height(48).color(Color.Blue)
          Text('Rendering...')
            .fontSize(12).fontColor(Color.Gray).margin({ left: 8 })
        }
        .justifyContent(FlexAlign.Center)
      } else if (this.errorText.length > 0) {
        Text(this.errorText)
          .fontSize(14).fontColor(Color.Red).textAlign(TextAlign.Center)
      } else if (this.pixelMap !== undefined) {
        Stack() {
          Image(this.pixelMap)
            .objectFit(ImageFit.Contain)
            .width('100%')
            .borderRadius(2)
          /* search hit highlights (yellow) */
          ForEach(this.searchHits, (hit: TextRect) => {
            Column()
              .width(`${(hit.x1 - hit.x0) * 100}%`)
              .height(`${(hit.y1 - hit.y0) * 100}%`)
              .position({ x: `${hit.x0 * 100}%`, y: `${hit.y0 * 100}%` })
              .backgroundColor('#33ffeb3b')
          }, (hit: TextRect, idx: number) => `hit_${idx}_${hit.x0}`)

          /* annotation highlights (orange, wider) */
          ForEach(this.annotations, (ann: AnnotationInfo) => {
            if (ann.type === 'highlight') {
              Column()
                .width(`${(ann.x1 - ann.x0) * 100}%`)
                .height(`${(ann.y1 - ann.y0) * 100}%`)
                .position({ x: `${ann.x0 * 100}%`, y: `${ann.y0 * 100}%` })
                .backgroundColor('#55ff9800')
            }
          }, (ann: AnnotationInfo, idx: number) => `ann_${idx}_${ann.x0}`)
        }
        .width('100%')
      } else {
        Column() {
          Text(`Page ${this.pageIndex + 1}`)
            .fontSize(18).fontColor(Color.Gray)
        }
        .width('80%').height('70%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#F5F5F5').borderRadius(8)
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * TOC sidebar panel.
 */
@Component
struct TocPanel {
  @State tocEntries: TocEntry[] = [];
  onJumpPage: (page: number) => void = () => {};

  aboutToAppear(): void {
    this.loadToc();
  }

  async loadToc(): Promise<void> {
    /* TOC is loaded by the parent and passed via refresh */
  }

  setEntries(entries: TocEntry[]): void {
    this.tocEntries = entries;
    console.info(`[Reader] TOC loaded: ${entries.length} entries`);
  }

  build() {
    Column() {
      Text('目录 (TOC)')
        .fontSize(16).fontWeight(FontWeight.Bold)
        .margin({ bottom: 8 })

      if (this.tocEntries.length === 0) {
        Text('(无目录)')
          .fontSize(13).fontColor(Color.Gray)
      } else {
        List() {
          ForEach(this.tocEntries, (entry: TocEntry) => {
            ListItem() {
              Row() {
                Text(entry.title.length > 24 ? entry.title.substring(0, 24) + '…' : entry.title)
                  .fontSize(13).fontColor('#333')
                  .margin({ left: entry.depth * 12 })
                  .layoutWeight(1)
                if (entry.page >= 0) {
                  Text(`p${entry.page + 1}`)
                    .fontSize(11).fontColor(Color.Gray)
                }
              }
              .width('100%')
              .padding({ top: 6, bottom: 6, left: 8, right: 8 })
              .onClick(() => {
                if (entry.page >= 0) {
                  console.info(`[Reader] TOC jump to page ${entry.page + 1}`);
                  this.onJumpPage(entry.page);
                }
              })
            }
          }, (item: TocEntry, index?: number) => `toc_${index}_${item.title}`)
        }
        .layoutWeight(1)
      }
    }
    .width('85%')
    .height('100%')
    .backgroundColor('#fff')
    .padding(12)
    .shadow({ radius: 8, color: '#40000000', offsetX: -2, offsetY: 0 })
  }
}

/**
 * Double-page renderer: left page = pageIndex*2, right page = pageIndex*2+1.
 * Used in double-page (双页) reading mode.
 */
@Component
struct DoublePageRenderer {
  pageIndex: number = 0; /* the pair index */
  dataSource: PageDataSource = new PageDataSource();
  @State leftPage: number = 0;
  @State rightPage: number = 0;

  aboutToAppear(): void {
    this.leftPage = this.pageIndex * 2;
    this.rightPage = this.pageIndex * 2 + 1;
  }

  build() {
    Row({ space: 2 }) {
      PageRenderer({ pageIndex: this.leftPage, dataSource: this.dataSource })
        .layoutWeight(1)
      if (this.rightPage < this.dataSource.getPageCount()) {
        PageRenderer({ pageIndex: this.rightPage, dataSource: this.dataSource })
          .layoutWeight(1)
      } else {
        Column()
          .layoutWeight(1)
          .height('100%')
          .backgroundColor('#F5F5F5')
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * Musician page renderer: shows one half of the page at a time via crop,
 * tapping the right zone advances top -> bottom -> next page.
 */
const MUSICIAN_TOP: CropRect = { x0: 0, y0: 0, x1: 1, y1: 0.5 };
const MUSICIAN_BOTTOM: CropRect = { x0: 0, y0: 0.5, x1: 1, y1: 1 };

@Component
struct MusicianPageRenderer {
  pageIndex: number = 0;
  dataSource: PageDataSource = new PageDataSource();
  @State half: number = 0; /* 0=top half, 1=bottom half */
  onHalfChange: (page: number, half: number) => void = () => {};

  build() {
    Column() {
      if (this.half === 0) {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_TOP })
          .width('100%')
          .height('100%')
      } else {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_BOTTOM })
          .width('100%')
          .height('100%')
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * Main Reader component.
 */
@Entry
@Component
struct Reader {
  @State currentPage: number = 0;
  @State totalPages: number = 0;
  @State showMenu: boolean = false;
  @State pageList: number[] = [];
  @State docTitle: string = '';
  @State scrollMode: number = 0; /* 0=horizontal Swiper, 1=vertical List */
  @State pageMode: number = 0;   /* 0=single, 1=double, 2=musician */
  @State zoomLevel: number = 1.5;
  @State nightMode: boolean = false;
  @State showToc: boolean = false;
  @State tocEntries: TocEntry[] = [];
  @State showSearch: boolean = false;
  @State searchQuery: string = '';
  @State searchHits: TextRect[] = [];       /* normalized hits on the current page */
  @State searchHitPage: number = -1;        /* page the hits belong to */
  @State brightnessLevel: number = 128;     /* 1..255 system brightness */
  @State autoFlip: boolean = false;
  @State autoFlipInterval: number = 3;      /* seconds between flips */
  @State showBookmarks: boolean = false;    /* bookmark list panel */
  @State bookmarks: Bookmark[] = [];
  @State annotations: AnnotationInfo[] = []; /* current page annotations */
  @State drawMode: boolean = false;         /* freehand drawing mode */
  @State drawPoints: TextRect[] = [];
  private flipTimerId: number = -1;
  private documentPath: string = '';
  private dataSource: PageDataSource = new PageDataSource();

  /* ---- TTS reading (D1) ---- */
  @State showTts: boolean = false;
  @State ttsStatus: string = '就绪';
  @State ttsEngineOk: boolean = true;
  @State ttsRate: number = 1.0;             /* 0.5..2.0 */
  @State ttsPitch: number = 1.0;            /* 0.5..2.0 */
  @State ttsSentences: string[] = [];
  @State ttsIndex: number = 0;
  @State ttsPaused: boolean = false;
  private ttsStopped: boolean = true;
  private ttsPollId: number = -1;
  private ttsPollBusy: boolean = false;
  private ttsAdvancing: boolean = false;

  /* ---- Speed reading RSVP (D2) ---- */
  @State showRsvp: boolean = false;
  @State rsvpWord: string = '';
  @State rsvpProgress: string = '0/0';
  @State rsvpWpm: number = 300;             /* 100..800 */
  @State rsvpRunning: boolean = false;
  @State rsvpPaused: boolean = false;
  private rsvpTokens: string[] = [];
  private rsvpIndex: number = 0;
  private rsvpTimerId: number = -1;
  private rsvpAdvancing: boolean = false;

  /* Hidden ArkWeb host that exposes speechSynthesis for TTS */
  private webController: webview.WebviewController = new webview.WebviewController();

  /* ---- Settings & theme (sprint E) ---- */
  @State showSettings: boolean = false;
  @State theme: number = 0;             /* 0=light 1=dark 2=oled 3=ink */
  @State isReflow: boolean = false;     /* reflowable (EPUB/HTML) vs fixed (PDF) */
  @State fontSize: number = 16;         /* em, reflowable only */
  @State lineHeight: number = 1.6;      /* CSS line-height, reflowable only */
  @State pageMargin: number = 20;       /* CSS body margin pt, reflowable only */
  @State reflowVersion: number = 0;     /* bump to force page-list re-render */

  aboutToAppear(): void {
    try {
      const ctx = getContext(this);
      this.initSettings(ctx).then(() => {
        const params = router.getParams() as Record<string, string>;
        if (params !== null && params !== undefined && params['docPath'] !== undefined) {
          this.documentPath = params['docPath'];
          this.docTitle = params['title'] !== undefined ? params['title'] : 'Document';
          console.info(`[Reader] Opening picked file: ${this.documentPath}`);
        } else {
          this.documentPath = ctx.cacheDir + '/test.pdf';
          this.docTitle = 'test.pdf';
          this.ensureTestPdf(this.documentPath).then(() => {
            this.initReader();
          }).catch((err: Error) => {
            console.error('[Reader] Init failed: ' + JSON.stringify(err));
          });
          return;
        }

        getPageForPath(ctx, this.documentPath).then((savedPage: number) => {
          this.currentPage = savedPage;
          console.info(`[Reader] Restoring page ${savedPage + 1}`);
          this.initReader();
        }).catch(() => {
          this.initReader();
        });
      });
    } catch (e) {
      console.error('[Reader] Init failed');
    }
  }

  /** Load persisted settings and apply them to the runtime state. */
  private async initSettings(ctx: Context): Promise<void> {
    const s: ReaderSettings = await loadSettings(ctx);
    this.applySettings(s);
    console.info(`[Reader] Settings applied: theme=${s.theme} zoom=${s.zoom.toFixed(2)} font=${s.fontSize} lh=${s.lineHeight.toFixed(1)}`);
  }

  private applySettings(s: ReaderSettings): void {
    this.theme = s.theme;
    this.zoomLevel = s.zoom;
    this.scrollMode = s.scrollMode;
    this.pageMode = s.pageMode;
    this.nightMode = s.theme === 1 || s.theme === 2;
    this.brightnessLevel = s.brightness;
    this.autoFlip = s.autoFlip;
    this.autoFlipInterval = s.autoFlipInterval;
    this.ttsRate = s.ttsRate;
    this.ttsPitch = s.ttsPitch;
    this.rsvpWpm = s.rsvpWpm;
    this.fontSize = s.fontSize;
    this.lineHeight = s.lineHeight;
    this.pageMargin = s.margin;
    this.dataSource.setZoom(this.zoomLevel);
    this.dataSource.setInvert(this.nightMode);
    if (this.brightnessLevel > 0) {
      try {
        brightness.setValue({ value: this.brightnessLevel });
      } catch (e) {
        /* brightness may be unavailable on some devices */
      }
    }
  }

  /** Persist the current runtime settings (called on exit and panel changes). */
  private async persistSettings(): Promise<void> {
    const s: ReaderSettings = {
      theme: this.theme,
      zoom: this.zoomLevel,
      scrollMode: this.scrollMode,
      pageMode: this.pageMode,
      nightMode: this.nightMode,
      autoFlip: this.autoFlip,
      autoFlipInterval: this.autoFlipInterval,
      brightness: this.brightnessLevel,
      ttsRate: this.ttsRate,
      ttsPitch: this.ttsPitch,
      rsvpWpm: this.rsvpWpm,
      fontSize: this.fontSize,
      lineHeight: this.lineHeight,
      margin: this.pageMargin
    };
    await saveSettings(getContext(this), s);
  }

  /** Apply a theme: drives invert + UI chrome colors. */
  private setTheme(t: number): void {
    this.theme = t;
    this.nightMode = t === 1 || t === 2;
    this.dataSource.setInvert(this.nightMode);
    this.persistSettings();
    console.info(`[Reader] Theme: ${THEME_NAMES[t]}`);
  }

  /** Reading-area / bottom-bar background per theme. */
  private chromeBg(): string {
    if (this.theme === 3) {
      return '#eaddc3'; /* ink: warm paper */
    }
    if (this.nightMode) {
      return '#101010';
    }
    return '#f5f5f5';
  }

  /** Top-bar background per theme. */
  private barBg(): string {
    if (this.theme === 3) {
      return '#4a3a28'; /* ink: dark brown bar */
    }
    if (this.nightMode) {
      return '#1a1a1a';
    }
    return '#333333';
  }

  /** Re-layout a reflowable doc with the current typography settings. */
  private async applyReflow(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || !this.isReflow) {
      return;
    }
    try {
      const css = `body{margin:${this.pageMargin}pt;line-height:${this.lineHeight.toFixed(2)}}`;
      layoutDocument(handle, REFLOW_W, REFLOW_H, this.fontSize, css);
      const newTotal: number = pageCount(handle);
      this.pageList = [];
      for (let i = 0; i < newTotal; i++) {
        this.pageList.push(i);
      }
      this.totalPages = newTotal;
      this.currentPage = Math.min(this.currentPage, newTotal - 1);
      this.reflowVersion++;
      this.onPageChanged(this.currentPage);
      this.loadToc();
      this.persistSettings();
      console.info(`[Reader] Reflow applied: ${newTotal} pages (em=${this.fontSize}, lh=${this.lineHeight.toFixed(1)}, m=${this.pageMargin})`);
    } catch (e) {
      console.error('[Reader] applyReflow failed');
    }
  }

  private initReader(): void {
    try {
      this.dataSource.openDoc(this.documentPath);
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      if (handle !== undefined) {
        try {
          this.isReflow = isReflowable(handle);
          console.info(`[Reader] Reflowable document: ${this.isReflow}`);
        } catch (e) {
          this.isReflow = false;
        }
        if (this.isReflow) {
          try {
            const css = `body{margin:${this.pageMargin}pt;line-height:${this.lineHeight.toFixed(2)}}`;
            layoutDocument(handle, REFLOW_W, REFLOW_H, this.fontSize, css);
            console.info(`[Reader] Reflow layout: em=${this.fontSize} lh=${this.lineHeight.toFixed(1)} m=${this.pageMargin}`);
          } catch (e) {
            console.error('[Reader] Reflow layout failed');
          }
        }
      }
      this.totalPages = this.dataSource.getPageCount();
      for (let i = 0; i < this.totalPages; i++) {
        this.pageList.push(i);
      }
      /* Load TOC, bookmarks and page annotations in background */
      this.loadToc();
      this.loadBookmarks();
      this.refreshAnnotations();
      console.info(`[Reader] Document opened: ${this.totalPages} pages, starting at page ${this.currentPage + 1}`);
    } catch (e) {
      console.error('[Reader] Failed to open document: ' + JSON.stringify(e));
    }
  }

  private async loadToc(): Promise<void> {
    try {
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      if (handle !== undefined) {
        this.tocEntries = getToc(handle);
        console.info(`[Reader] TOC loaded: ${this.tocEntries.length} entries`);
      }
    } catch (e) {
      console.info('[Reader] No TOC available');
    }
  }

  private async ensureTestPdf(target: string): Promise<void> {
    try {
      const content: Uint8Array = await getContext(this).resourceManager.getRawFileContent('test.pdf');
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content.buffer);
      fs.closeSync(file);
      console.info(`[Reader] test.pdf extracted to ${target}, size=${content.byteLength}`);
    } catch (e) {
      console.warn('[Reader] test.pdf already exists or extract failed');
    }
  }

  aboutToDisappear(): void {
    this.stopAutoFlip();
    this.ttsStopped = true;
    this.stopTtsPoll();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    this.stopRsvpTimer();
    this.persistSettings();
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: this.currentPage,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString(),
        star: false,
        coverPath: '',
        author: ''
      };
      saveRecentBook(ctx, book);
    }
    this.dataSource.closeDoc();
  }

  private onPageChanged(index: number): void {
    const prevPage = this.currentPage;
    this.currentPage = index;
    console.info(`[Reader] Page changed to ${index + 1}/${this.totalPages}`);
    this.saveProgress(index);
    this.refreshAnnotations();
    /* Re-anchor active reading modes (TTS / RSVP) to the new page */
    if (this.showTts && !this.ttsStopped && !this.ttsAdvancing) {
      this.ttsAdvancing = true;
      this.ttsIndex = 0;
      this.loadTtsSentences(index).then(() => {
        this.ttsAdvancing = false;
        if (this.showTts && !this.ttsStopped) {
          this.ttsSpeakCurrent();
        }
      });
    }
    if (this.showRsvp && this.rsvpRunning && !this.rsvpAdvancing) {
      this.rsvpIndex = 0;
      this.loadRsvpTokens(index).then(() => {
        if (this.showRsvp && this.rsvpRunning) {
          this.updateRsvpDisplay();
        }
      });
    }
  }

  private saveProgress(page: number): void {
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: page,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString(),
        star: false,
        coverPath: '',
        author: ''
      };
      saveRecentBook(ctx, book);
    }
  }

  private zoomIn(): void {
    const newZoom = Math.min(this.zoomLevel + ZOOM_STEP, MAX_ZOOM);
    if (newZoom !== this.zoomLevel) {
      this.zoomLevel = newZoom;
      this.dataSource.setZoom(newZoom);
      console.info(`[Reader] Zoom in: ${this.zoomLevel.toFixed(2)}x`);
    }
  }

  private zoomOut(): void {
    const newZoom = Math.max(this.zoomLevel - ZOOM_STEP, MIN_ZOOM);
    if (newZoom !== this.zoomLevel) {
      this.zoomLevel = newZoom;
      this.dataSource.setZoom(newZoom);
      console.info(`[Reader] Zoom out: ${this.zoomLevel.toFixed(2)}x`);
    }
  }

  private toggleNightMode(): void {
    this.nightMode = !this.nightMode;
    this.theme = this.nightMode ? 1 : 0;
    this.dataSource.setInvert(this.nightMode);
    this.persistSettings();
    console.info(`[Reader] Night mode: ${this.nightMode ? 'ON' : 'OFF'} (theme=${THEME_NAMES[this.theme]})`);
  }

  private toggleScrollMode(): void {
    this.scrollMode = this.scrollMode === 0 ? 1 : 0;
    console.info(`[Reader] Scroll mode: ${this.scrollMode === 0 ? 'horizontal' : 'vertical'}`);
  }

  private togglePageMode(): void {
    this.pageMode = (this.pageMode + 1) % 3;
    /* keep the current page visible after mode switch */
    this.currentPage = Math.min(this.currentPage, this.totalPages - 1);
    const names: string[] = ['single', 'double', 'musician'];
    console.info(`[Reader] Page mode: ${names[this.pageMode]}`);
  }

  private openSearch(): void {
    this.showSearch = true;
    this.searchHits = [];
    this.searchHitPage = -1;
    console.info('[Reader] Search bar opened');
  }

  private closeSearch(): void {
    this.showSearch = false;
    this.searchHits = [];
    this.searchHitPage = -1;
    console.info('[Reader] Search bar closed');
  }

  /** Search the whole book, jumping to the first page with hits. */
  private async doSearch(): Promise<void> {
    const q: string = this.searchQuery.trim();
    if (q.length === 0 || this.totalPages <= 0) {
      return;
    }
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    console.info(`[Reader] Search '${q}'...`);
    for (let p = 0; p < this.totalPages; p++) {
      try {
        const rects: TextRect[] = searchText(handle, q, p);
        if (rects.length > 0) {
          /* convert pt*1000 coords to normalized 0..1 using page size */
          const size: TextRect = getPageSize(handle, p);
          const pw = (size.x1 - size.x0) > 0 ? (size.x1 - size.x0) : 1;
          const ph = (size.y1 - size.y0) > 0 ? (size.y1 - size.y0) : 1;
          const norm: TextRect[] = [];
          for (let i = 0; i < rects.length; i++) {
            const n: TextRect = {
              x0: (rects[i].x0 - size.x0) / pw,
              y0: (rects[i].y0 - size.y0) / ph,
              x1: (rects[i].x1 - size.x0) / pw,
              y1: (rects[i].y1 - size.y0) / ph
            };
            norm.push(n);
          }
          this.searchHits = norm;
          this.searchHitPage = p;
          this.currentPage = p;
          this.onPageChanged(p);
          console.info(`[Reader] Search '${q}': ${norm.length} hits on page ${p + 1}`);
          return;
        }
      } catch (e) {
        console.error(`[Reader] Search page ${p} failed`);
      }
    }
    console.info(`[Reader] Search '${q}': no hits`);
    this.searchHits = [];
    this.searchHitPage = -1;
  }

  /** Jump to the next page that contains hits. */
  private nextHitPage(): void {
    if (this.searchQuery.length === 0) {
      return;
    }
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    for (let p = this.currentPage + 1; p < this.totalPages; p++) {
      try {
        const rects: TextRect[] = searchText(handle, this.searchQuery, p);
        if (rects.length > 0) {
          this.searchHits = rects;
          this.searchHitPage = p;
          this.currentPage = p;
          this.onPageChanged(p);
          console.info(`[Reader] Next hit on page ${p + 1}: ${rects.length}`);
          return;
        }
      } catch (e) {
        /* continue */
      }
    }
    console.info('[Reader] No more hits');
  }

  // ---- Bookmarks (C4) ----
  private async toggleBookmark(): Promise<void> {
    const ctx = getContext(this);
    if (this.documentPath.length === 0) {
      return;
    }
    const list = await getBookmarks(ctx, this.documentPath);
    let found = false;
    for (let i = 0; i < list.length; i++) {
      if (list[i].page === this.currentPage) {
        found = true;
        break;
      }
    }
    if (found) {
      await removeBookmark(ctx, this.documentPath, this.currentPage);
      console.info(`[Reader] Bookmark removed at page ${this.currentPage + 1}`);
    } else {
      const ok = await addBookmark(ctx, this.documentPath, this.currentPage, '');
      if (ok) {
        console.info(`[Reader] Bookmark added at page ${this.currentPage + 1}`);
      }
    }
    await this.loadBookmarks();
  }

  private async loadBookmarks(): Promise<void> {
    if (this.documentPath.length === 0) {
      this.bookmarks = [];
      return;
    }
    const ctx = getContext(this);
    this.bookmarks = await getBookmarks(ctx, this.documentPath);
    console.info(`[Reader] Bookmarks loaded: ${this.bookmarks.length}`);
  }

  private jumpToBookmark(page: number): void {
    this.currentPage = Math.max(0, Math.min(page, this.totalPages - 1));
    this.onPageChanged(this.currentPage);
    this.showBookmarks = false;
    console.info(`[Reader] Jumped to bookmark page ${page + 1}`);
  }

  // ---- Annotations (C2 highlight) ----
  private async refreshAnnotations(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      this.annotations = getAnnotations(handle, this.currentPage);
      console.info(`[Reader] Page ${this.currentPage + 1} annotations: ${this.annotations.length}`);
    } catch (e) {
      this.annotations = [];
      console.error('[Reader] getAnnotations failed');
    }
  }

  /** Add a highlight over the current search hit region (or page center). */
  private async addHighlightOnPage(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    let x0 = 0.2, y0 = 0.3, x1 = 0.8, y1 = 0.5; /* default region */
    if (this.searchHits.length > 0) {
      x0 = this.searchHits[0].x0;
      y0 = this.searchHits[0].y0;
      x1 = this.searchHits[0].x1;
      y1 = this.searchHits[0].y1;
    }
    try {
      addHighlight(handle, this.currentPage, x0, y0, x1, y1, '#ffff00');
      await this.refreshAnnotations();
      console.info(`[Reader] Highlight added on page ${this.currentPage + 1}`);
    } catch (e) {
      console.error('[Reader] addHighlight failed');
    }
  }

  private async deleteAnnotationAt(index: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      deleteAnnotation(handle, this.currentPage, index);
      await this.refreshAnnotations();
      console.info(`[Reader] Annotation ${index} deleted`);
    } catch (e) {
      console.error('[Reader] deleteAnnotation failed');
    }
  }

  private async saveAnnotatedDoc(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.documentPath.length === 0) {
      return;
    }
    try {
      saveDocument(handle, this.documentPath);
      console.info('[Reader] Document saved with annotations');
    } catch (e) {
      console.error('[Reader] saveDocument failed');
    }
  }

  // ---- Freehand drawing (C3) ----
  private toggleDrawMode(): void {
    this.drawMode = !this.drawMode;
    this.drawPoints = [];
    console.info(`[Reader] Draw mode: ${this.drawMode ? 'ON' : 'OFF'}`);
  }

  private onDrawTouch(event: TouchEvent): void {
    if (!this.drawMode) {
      return;
    }
    if (event.touches.length > 0) {
      const t = event.touches[0];
      const x = t.x;
      const y = t.y;
      /* capture points as normalized coords approximated via container size */
      this.drawPoints.push({ x0: x, y0: y, x1: x, y1: y });
    }
    if (event.type === TouchType.Up && this.drawPoints.length > 1) {
      this.finishInkStroke();
    }
  }

  private async finishInkStroke(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.drawPoints.length < 2) {
      this.drawPoints = [];
      return;
    }
    /* normalize using a nominal 1000x1500 coordinate space (matches reader area) */
    const norm: TextRect[] = [];
    for (let i = 0; i < this.drawPoints.length; i++) {
      norm.push({
        x0: this.drawPoints[i].x0 / 1000.0,
        y0: this.drawPoints[i].y0 / 1500.0,
        x1: 0, y1: 0
      });
    }
    try {
      addInkStroke(handle, this.currentPage, norm);
      await this.refreshAnnotations();
      console.info(`[Reader] Ink stroke saved (${norm.length} points)`);
    } catch (e) {
      console.error('[Reader] addInkStroke failed');
    }
    this.drawPoints = [];
  }

  private setBrightness(level: number): void {
    this.brightnessLevel = Math.max(1, Math.min(255, Math.round(level)));
    try {
      brightness.setValue({ value: this.brightnessLevel });
      console.info(`[Reader] Brightness: ${this.brightnessLevel}`);
    } catch (e) {
      console.error('[Reader] Brightness set failed');
    }
  }

  private toggleAutoFlip(): void {
    this.autoFlip = !this.autoFlip;
    if (this.autoFlip) {
      this.startAutoFlip();
    } else {
      this.stopAutoFlip();
    }
    console.info(`[Reader] Auto-flip: ${this.autoFlip ? 'ON' : 'OFF'} (${this.autoFlipInterval}s)`);
  }

  private startAutoFlip(): void {
    this.stopAutoFlip();
    this.flipTimerId = setInterval(() => {
      if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
        this.currentPage = this.currentPage + 1;
        this.onPageChanged(this.currentPage);
      } else {
        /* reached the end: stop */
        this.autoFlip = false;
        this.stopAutoFlip();
        console.info('[Reader] Auto-flip: finished (last page)');
      }
    }, this.autoFlipInterval * 1000);
  }

  private stopAutoFlip(): void {
    if (this.flipTimerId >= 0) {
      clearInterval(this.flipTimerId);
      this.flipTimerId = -1;
    }
  }

  // ---- TTS reading (D1) ----

  private openTtsPanel(): void {
    this.showRsvp = false;
    this.stopRsvpTimer();
    this.showTts = true;
    this.ttsStopped = false;
    this.ttsPaused = false;
    this.ttsIndex = 0;
    this.ttsStatus = '准备中...';
    this.probeTtsEngine();
    this.loadTtsSentences(this.currentPage).then(() => {
      console.info(`[Reader] TTS panel opened: page ${this.currentPage + 1}, ${this.ttsSentences.length} sentences`);
      if (this.showTts && !this.ttsStopped) {
        this.ttsSpeakCurrent();
      }
    });
  }

  private closeTtsPanel(): void {
    this.ttsStopped = true;
    this.stopTtsPoll();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    this.showTts = false;
    console.info('[Reader] TTS panel closed');
  }

  private async probeTtsEngine(): Promise<void> {
    try {
      const res: string = await this.webController.runJavaScript('ttsProbe()');
      const count: number = parseInt(res, 10);
      if (count <= 0) {
        this.ttsEngineOk = false;
        this.ttsStatus = count === -1 ? '语音引擎不可用' : '未检测到语音包';
        console.warn(`[Reader] TTS probe: ${count}`);
      } else {
        this.ttsEngineOk = true;
        console.info(`[Reader] TTS probe: ${count} voices`);
      }
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStatus = 'Web 引擎不可用';
      console.error('[Reader] TTS probe failed');
    }
  }

  private async loadTtsSentences(page: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.ttsSentences = [];
      return;
    }
    try {
      const raw: string = getText(handle, page, 1.0);
      this.ttsSentences = TextUtils.splitSentences(TextUtils.normalize(raw));
      this.ttsIndex = 0;
    } catch (e) {
      this.ttsSentences = [];
      console.error('[Reader] TTS getText failed');
    }
  }

  private async ttsSpeakCurrent(): Promise<void> {
    if (this.ttsStopped || !this.showTts) {
      return;
    }
    if (this.ttsIndex >= this.ttsSentences.length) {
      this.ttsAdvancePage();
      return;
    }
    const sent: string = this.ttsSentences[this.ttsIndex].trim();
    if (sent.length === 0) {
      this.ttsIndex++;
      this.ttsSpeakCurrent();
      return;
    }
    try {
      const res: string = await this.webController.runJavaScript(
        `ttsSpeak(${JSON.stringify(sent)},${this.ttsRate},${this.ttsPitch},${this.ttsIndex})`);
      const code: number = parseInt(res, 10);
      if (code !== 0) {
        this.ttsEngineOk = false;
        this.ttsStopped = true;
        this.ttsStatus = code === -2 ? '未检测到语音包' : '语音引擎不可用';
        this.stopTtsPoll();
        console.warn(`[Reader] TTS speak rejected: ${code}`);
        return;
      }
      this.ttsStatus = '朗读中';
      this.ttsPaused = false;
      this.startTtsPoll();
      console.info(`[Reader] TTS speaking ${this.ttsIndex + 1}/${this.ttsSentences.length} (page ${this.currentPage + 1})`);
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStopped = true;
      this.ttsStatus = 'Web 引擎不可用';
      this.stopTtsPoll();
      console.error('[Reader] TTS speak JS call failed');
    }
  }

  private startTtsPoll(): void {
    this.stopTtsPoll();
    this.ttsPollId = setInterval(() => {
      this.ttsPollTick();
    }, 400);
  }

  private stopTtsPoll(): void {
    if (this.ttsPollId >= 0) {
      clearInterval(this.ttsPollId);
      this.ttsPollId = -1;
    }
  }

  private async ttsPollTick(): Promise<void> {
    if (this.ttsPollBusy) {
      return;
    }
    this.ttsPollBusy = true;
    try {
      if (this.ttsStopped || !this.showTts) {
        this.stopTtsPoll();
        return;
      }
      const res: string = await this.webController.runJavaScript('ttsStatus()');
      const code: number = parseInt(res, 10);
      if (code === 2) {
        this.ttsPaused = true;
        this.ttsStatus = '已暂停';
        return;
      }
      if (code === 1) {
        this.ttsPaused = false;
        return;
      }
      /* idle (0) or engine gone (-1): current sentence finished → speak next */
      this.ttsPaused = false;
      this.ttsIndex++;
      this.ttsSpeakCurrent();
    } catch (e) {
      this.stopTtsPoll();
    } finally {
      this.ttsPollBusy = false;
    }
  }

  private ttsTogglePause(): void {
    if (this.ttsStopped) {
      return;
    }
    if (this.ttsPaused) {
      this.webController.runJavaScript('ttsResume()').then((r: string) => {
        this.ttsPaused = false;
        this.ttsStatus = '朗读中';
        console.info('[Reader] TTS resumed');
      }).catch(() => {
      });
    } else {
      this.webController.runJavaScript('ttsPause()').then((r: string) => {
        this.ttsPaused = true;
        this.ttsStatus = '已暂停';
        console.info('[Reader] TTS paused');
      }).catch(() => {
      });
    }
  }

  private ttsStop(): void {
    this.ttsStopped = true;
    this.ttsPaused = false;
    this.stopTtsPoll();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    this.ttsStatus = '已停止';
    console.info('[Reader] TTS stopped');
  }

  private ttsPrevSentence(): void {
    if (this.ttsSentences.length === 0) {
      return;
    }
    this.ttsIndex = Math.max(0, this.ttsIndex - 1);
    this.ttsSpeakCurrent();
  }

  private ttsNextSentence(): void {
    if (this.ttsSentences.length === 0) {
      return;
    }
    this.ttsIndex = Math.min(this.ttsIndex + 1, this.ttsSentences.length - 1);
    this.ttsSpeakCurrent();
  }

  private ttsAdvancePage(): void {
    if (this.currentPage >= this.totalPages - 1) {
      this.ttsStopped = true;
      this.stopTtsPoll();
      this.ttsStatus = '朗读完成';
      console.info('[Reader] TTS finished (last page)');
      return;
    }
    this.currentPage = this.currentPage + 1;
    this.ttsAdvancing = true;
    this.onPageChanged(this.currentPage);
    this.ttsAdvancing = false;
    this.ttsIndex = 0;
    this.loadTtsSentences(this.currentPage).then(() => {
      if (this.showTts && !this.ttsStopped) {
        this.ttsSpeakCurrent();
      }
    });
  }

  private setTtsRate(v: number): void {
    this.ttsRate = Math.round(v * 10) / 10;
    console.info(`[Reader] TTS rate: ${this.ttsRate.toFixed(1)}x`);
    if (!this.ttsStopped && this.showTts) {
      this.ttsSpeakCurrent();
    }
  }

  private setTtsPitch(v: number): void {
    this.ttsPitch = Math.round(v * 10) / 10;
    console.info(`[Reader] TTS pitch: ${this.ttsPitch.toFixed(1)}x`);
    if (!this.ttsStopped && this.showTts) {
      this.ttsSpeakCurrent();
    }
  }

  // ---- Speed reading RSVP (D2) ----

  private openRsvpPanel(): void {
    this.showTts = false;
    this.ttsStopped = true;
    this.stopTtsPoll();
    this.showRsvp = true;
    this.rsvpPaused = false;
    this.rsvpIndex = 0;
    this.loadRsvpTokens(this.currentPage).then(() => {
      this.updateRsvpDisplay();
      console.info(`[Reader] RSVP panel opened: ${this.rsvpTokens.length} tokens on page ${this.currentPage + 1}`);
    });
  }

  private closeRsvpPanel(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = false;
    this.showRsvp = false;
    console.info('[Reader] RSVP panel closed');
  }

  private async loadRsvpTokens(page: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.rsvpTokens = [];
      return;
    }
    try {
      const raw: string = getText(handle, page, 1.0);
      this.rsvpTokens = TextUtils.splitTokens(TextUtils.normalize(raw));
      this.rsvpIndex = 0;
    } catch (e) {
      this.rsvpTokens = [];
      console.error('[Reader] RSVP getText failed');
    }
  }

  private updateRsvpDisplay(): void {
    const idx: number = Math.min(this.rsvpIndex, this.rsvpTokens.length - 1);
    this.rsvpWord = idx >= 0 ? this.rsvpTokens[idx] : '';
    this.rsvpProgress = `${Math.min(this.rsvpIndex + 1, this.rsvpTokens.length)}/${this.rsvpTokens.length}`;
  }

  private rsvpToggle(): void {
    if (this.rsvpRunning) {
      return;
    }
    this.rsvpPaused = false;
    if (this.rsvpIndex >= this.rsvpTokens.length) {
      this.rsvpIndex = 0;
    }
    if (this.rsvpTokens.length === 0) {
      this.loadRsvpTokens(this.currentPage).then(() => {
        if (this.showRsvp) {
          this.updateRsvpDisplay();
          this.rsvpBegin();
        }
      });
      return;
    }
    this.rsvpBegin();
  }

  private rsvpBegin(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = true;
    this.rsvpPaused = false;
    const delay: number = Math.max(60, Math.round(60000 / this.rsvpWpm));
    this.rsvpTimerId = setInterval(() => {
      this.rsvpTick();
    }, delay);
    console.info(`[Reader] RSVP started: ${this.rsvpWpm} wpm, ${this.rsvpTokens.length} tokens`);
  }

  private rsvpPause(): void {
    if (!this.rsvpRunning) {
      return;
    }
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = true;
    console.info('[Reader] RSVP paused');
  }

  private rsvpStop(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = false;
    this.rsvpIndex = 0;
    this.updateRsvpDisplay();
    console.info('[Reader] RSVP stopped');
  }

  private rsvpTick(): void {
    if (this.rsvpIndex >= this.rsvpTokens.length) {
      this.rsvpAdvancePage();
      return;
    }
    this.updateRsvpDisplay();
    this.rsvpIndex++;
  }

  private rsvpAdvancePage(): void {
    if (this.currentPage >= this.totalPages - 1) {
      this.stopRsvpTimer();
      this.rsvpRunning = false;
      this.rsvpWord = '';
      this.rsvpProgress = '完成';
      console.info('[Reader] RSVP finished (last page)');
      return;
    }
    this.currentPage = this.currentPage + 1;
    this.rsvpAdvancing = true;
    this.onPageChanged(this.currentPage);
    this.rsvpAdvancing = false;
    this.rsvpIndex = 0;
    this.loadRsvpTokens(this.currentPage).then(() => {
      if (this.rsvpRunning) {
        this.updateRsvpDisplay();
        console.info(`[Reader] RSVP continue on page ${this.currentPage + 1}`);
      }
    });
  }

  private stopRsvpTimer(): void {
    if (this.rsvpTimerId >= 0) {
      clearInterval(this.rsvpTimerId);
      this.rsvpTimerId = -1;
    }
  }

  private setRsvpWpm(v: number): void {
    this.rsvpWpm = Math.round(v / 50) * 50;
    if (this.rsvpRunning) {
      this.stopRsvpTimer();
      const delay: number = Math.max(60, Math.round(60000 / this.rsvpWpm));
      this.rsvpTimerId = setInterval(() => {
        this.rsvpTick();
      }, delay);
    }
    console.info(`[Reader] RSVP speed: ${this.rsvpWpm} wpm`);
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      /* Main content area */
      Column() {
        // Top bar
        Row() {
          Text(this.docTitle.length > 12 ? this.docTitle.substring(0, 12) + '…' : (this.docTitle.length > 0 ? this.docTitle : ''))
            .fontSize(13).fontColor('#ccc').maxLines(1)
            .layoutWeight(1)

          Text(this.totalPages > 0 ? ` ${this.currentPage + 1}/${this.totalPages}` : 'Loading...')
            .fontSize(14).fontColor(Color.White)

          Blank()

          // TTS reading button (sprint D)
          Text('🔊')
            .fontSize(15).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => this.openTtsPanel())

          // Speed reading RSVP button (sprint D)
          Text('⚡')
            .fontSize(15).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => this.openRsvpPanel())

          // Menu button (explicit entry, not only center tap zone)
          Text('≡ 菜单')
            .fontSize(14).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => {
              this.showMenu = !this.showMenu;
              console.info(`[Reader] Menu toggled (top bar): ${this.showMenu}`);
            })

          // Bookmark toggle button
          Text('🔖')
            .fontSize(15).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => {
              this.toggleBookmark();
            })

          // Bookmark list button
          Text('📑')
            .fontSize(15).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => {
              this.loadBookmarks().then(() => {
                this.showBookmarks = true;
                console.info('[Reader] Bookmark panel opened');
              });
            })

          // Search button
          Text('🔍')
            .fontSize(15).fontColor(Color.White).margin({ right: 12 })
            .onClick(() => this.openSearch())

          // Mode indicator
          Text(this.scrollMode === 0 ? '⇄' : '⇅')
            .fontSize(16).fontColor('#aaa').margin({ right: 8 })
        }
        .width('100%').height(40)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(this.barBg())

        // Search bar (visible when opened)
        if (this.showSearch) {
          Row({ space: 8 }) {
            TextInput({ placeholder: '搜索...', text: this.searchQuery })
              .layoutWeight(1)
              .height(36)
              .fontSize(14)
              .backgroundColor('#fff')
              .borderRadius(4)
              .onChange((value: string) => {
                this.searchQuery = value;
              })
            Button('搜索')
              .fontSize(13).height(36)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => {
                this.doSearch();
              })
            Button('▾')
              .fontSize(13).height(36).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.nextHitPage())
            Button('✕')
              .fontSize(13).height(36).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.closeSearch())
          }
          .width('96%')
          .padding({ top: 6, bottom: 6 })
          .backgroundColor(this.nightMode ? '#222' : '#f0f0f0')
        }

        // Progress bar
        Progress({ value: this.totalPages > 1 ? (this.currentPage / (this.totalPages - 1)) * 100 : 0, total: 100 })
          .width('96%').height(4)
          .color(this.nightMode ? '#aaa' : Color.Blue)

        // Main reading area
        if (this.scrollMode === 0) {
          /* Horizontal Swiper mode */
          Stack() {
            Swiper() {
              ForEach(this.pageList, (pageIdx: number) => {
                if (this.pageMode === 1) {
                  /* double-page: each swiper page shows a pair */
                  DoublePageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
                } else if (this.pageMode === 2) {
                  MusicianPageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
                } else {
                  PageRenderer({
                    pageIndex: pageIdx,
                    dataSource: this.dataSource,
                    searchHits: pageIdx === this.searchHitPage ? this.searchHits : [],
                    annotations: pageIdx === this.currentPage ? this.annotations : []
                  })
                }
              }, (item: number) => `${item}_${this.zoomLevel}_${this.nightMode}_${this.pageMode}_${this.reflowVersion}`)
            }
            .index(this.currentPage)
            .width('100%').height('100%')
            .loop(false).indicator(false).autoPlay(false)
            .cachedCount(1)
            .onChange((index: number) => {
              this.onPageChanged(index);
            })

            /* Pinch gesture for zoom */
            Column()
              .width('100%').height('100%')
              .gesture(
                GestureGroup(GestureMode.Parallel,
                  PinchGesture({ fingers: 2 })
                    .onActionEnd((event: PinchGestureEvent) => {
                      if (event.scale > 1.05) {
                        this.zoomIn();
                      } else if (event.scale < 0.95) {
                        this.zoomOut();
                      }
                    }),
                  TapGesture({ count: 1 })
                    .onAction((event: TapGestureEvent) => {
                      /* Handled by tap zones below */
                    })
                )
              )

            // Left tap zone (previous page)
            Row()
              .width(`${LEFT_ZONE_RATIO * 100}%`).height('100%')
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                if (this.currentPage > 0) {
                  this.currentPage = this.currentPage - 1;
                  this.onPageChanged(this.currentPage);
                }
              })

            // Right tap zone (next page)
            Row()
              .width(`${RIGHT_ZONE_RATIO * 100}%`).height('100%')
              .align(Alignment.End)
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
                  this.currentPage = this.currentPage + 1;
                  this.onPageChanged(this.currentPage);
                }
              })

            // Center tap zone (toggle menu)
            Row()
              .width(`${(1 - LEFT_ZONE_RATIO - RIGHT_ZONE_RATIO) * 100}%`).height('100%')
              .align(Alignment.Center)
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                this.showMenu = !this.showMenu;
                console.info(`[Reader] Menu toggled: ${this.showMenu}`);
              })
          }
          .width('100%').layoutWeight(1)
        } else {
          /* Vertical continuous scroll mode */
          List() {
            ForEach(this.pageList, (pageIdx: number) => {
              ListItem() {
                PageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
              }
              .width('100%')
              .height('100vh')
            }, (item: number) => `v_${item}_${this.zoomLevel}_${this.nightMode}_${this.reflowVersion}`)
          }
          .width('100%').layoutWeight(1)
          .cachedCount(1)
          .scrollBar(BarState.Auto)
          .edgeEffect(EdgeEffect.Spring)
          .onScrollIndex((index: number) => {
            if (index !== this.currentPage) {
              this.onPageChanged(index);
            }
          })
        }

        // Bottom bar
        Row() {
          Text(`Zoom: ${this.zoomLevel.toFixed(2)}x`)
            .fontSize(12).fontColor(this.nightMode ? '#aaa' : Color.Gray)

          Blank()

          if (this.showMenu) {
            Text('☰')
              .fontSize(16).fontColor(Color.Blue)
              .margin({ right: 8 })
          }
        }
        .width('100%').height(32)
        .backgroundColor(this.chromeBg())
      }
      .width('100%').height('100%')
      .backgroundColor(this.chromeBg())

      /* Sepia wash for the ink theme (touches pass through) */
      if (this.theme === 3) {
        Column()
          .width('100%').height('100%')
          .backgroundColor('#2ED2B48C')
          .hitTestBehavior(HitTestMode.None)
      }

      /* Menu overlay (modal: full-screen dim + centered panel) */
      if (this.showMenu) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close menu */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showMenu = false;
              console.info('[Reader] Menu closed (background)');
            })

          Scroll() {
            Column({ space: 14 }) {
              Text('阅读设置')
                .fontSize(16).fontWeight(FontWeight.Bold)

          // Zoom controls
          Row({ space: 12 }) {
            Button('A-')
              .fontSize(14).height(36).width(50)
              .onClick(() => this.zoomOut())
            Text(`${Math.round(this.zoomLevel * 100)}%`)
              .fontSize(14).fontColor('#333').width(50).textAlign(TextAlign.Center)
            Button('A+')
              .fontSize(14).height(36).width(50)
              .onClick(() => this.zoomIn())
          }

          // Mode toggle
          Row({ space: 8 }) {
            Text('模式:')
              .fontSize(13).fontColor('#555')
            Button(this.scrollMode === 0 ? '水平翻页' : '垂直滚动')
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.toggleScrollMode())
          }

          // Page mode toggle (single/double/musician)
          Row({ space: 8 }) {
            Text('页面:')
              .fontSize(13).fontColor('#555')
            Button(this.pageMode === 0 ? '单页' : (this.pageMode === 1 ? '双页' : '乐谱'))
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.togglePageMode())
          }

          // Night mode toggle
          Row({ space: 8 }) {
            Text('夜间:')
              .fontSize(13).fontColor('#555')
            Button(this.nightMode ? '开' : '关')
              .fontSize(12).height(32)
              .backgroundColor(this.nightMode ? '#333' : '#e0e0e0')
              .fontColor(this.nightMode ? Color.White : '#333')
              .onClick(() => this.toggleNightMode())
          }

          // Brightness slider
          Row({ space: 8 }) {
            Text('亮度:')
              .fontSize(13).fontColor('#555')
            Slider({
              value: this.brightnessLevel,
              min: 1,
              max: 255,
              step: 4
            })
              .layoutWeight(1)
              .onChange((value: number) => {
                this.setBrightness(value);
              })
            Text(`${Math.round(this.brightnessLevel / 2.55)}%`)
              .fontSize(11).fontColor('#777').width(36)
          }

          // Auto-flip toggle
          Row({ space: 8 }) {
            Text('自动翻页:')
              .fontSize(13).fontColor('#555')
            Button(this.autoFlip ? '开' : '关')
              .fontSize(12).height(32)
              .backgroundColor(this.autoFlip ? '#2a7' : '#e0e0e0')
              .fontColor(this.autoFlip ? Color.White : '#333')
              .onClick(() => this.toggleAutoFlip())
            Text(`${this.autoFlipInterval}s`)
              .fontSize(11).fontColor('#777')
              .onClick(() => {
                this.autoFlipInterval = this.autoFlipInterval >= 5 ? 1 : this.autoFlipInterval + 1;
                if (this.autoFlip) {
                  this.startAutoFlip();
                }
                console.info(`[Reader] Auto-flip interval: ${this.autoFlipInterval}s`);
              })
          }

          // Annotations & bookmarks
          Row({ space: 8 }) {
            Button('高亮')
              .fontSize(12).height(32)
              .backgroundColor('#ff9800').fontColor(Color.White)
              .onClick(() => this.addHighlightOnPage())
            Button('手绘')
              .fontSize(12).height(32)
              .backgroundColor(this.drawMode ? '#333' : '#e0e0e0')
              .fontColor(this.drawMode ? Color.White : '#333')
              .onClick(() => this.toggleDrawMode())
            Button('保存')
              .fontSize(12).height(32)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => this.saveAnnotatedDoc())
          }

          if (this.annotations.length > 0) {
            Row({ space: 8 }) {
              Text(`标注 ${this.annotations.length}:`)
                .fontSize(12).fontColor('#555')
              Button(`删除第1条`)
                .fontSize(11).height(28)
                .backgroundColor('#e57373').fontColor(Color.White)
                .onClick(() => this.deleteAnnotationAt(0))
            }
          }

          // TTS & RSVP entries (sprint D)
          Row({ space: 8 }) {
            Button('🔊 TTS 朗读')
              .fontSize(12).height(32)
              .backgroundColor('#7b68ee').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openTtsPanel();
              })
            Button('⚡ 速读')
              .fontSize(12).height(32)
              .backgroundColor('#e91e63').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openRsvpPanel();
              })
          }

          // Settings button (sprint E)
          Row({ space: 8 }) {
            Button('⚙ 设置')
              .fontSize(12).height(32)
              .backgroundColor('#607d8b').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.showSettings = true;
                console.info('[Reader] Settings panel opened');
              })
          }

          // TOC button
          if (this.tocEntries.length > 0) {
            Button('目录 (TOC)')
              .fontSize(13).height(36)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => {
                this.showToc = true;
                console.info('[Reader] TOC panel opened');
              })
          }

          // Close menu
          Button('关闭')
            .fontSize(13).height(36)
            .backgroundColor('#f0f0f0').fontColor('#555')
            .onClick(() => {
              this.showMenu = false;
              console.info('[Reader] Menu closed');
            })
          }
          .width('100%')
          .padding(20)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
          }
          .width('72%')
          .height('85%')
          .align(Alignment.Center)
        }
      }

      /* TOC sidebar (slides in from right) */
      if (this.showToc) {
        Row() {
          /* Dimmed background to close TOC */
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#40000000')
            .onClick(() => {
              this.showToc = false;
              console.info('[Reader] TOC panel closed');
            })

          TocPanel({
            onJumpPage: (page: number) => {
              this.currentPage = page;
              this.onPageChanged(page);
              this.showToc = false;
            }
          })
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.End)
      }

      /* Bookmark panel (slides in from right) */
      if (this.showBookmarks) {
        Row() {
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#40000000')
            .onClick(() => {
              this.showBookmarks = false;
              console.info('[Reader] Bookmark panel closed');
            })

          Column() {
            Text('书签')
              .fontSize(16).fontWeight(FontWeight.Bold)
              .margin({ bottom: 8 })

            if (this.bookmarks.length === 0) {
              Text('(暂无书签，点顶栏 🔖 添加)')
                .fontSize(13).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.bookmarks, (bm: Bookmark) => {
                  ListItem() {
                    Row() {
                      Text(`p${bm.page + 1}  ${bm.label.length > 0 ? bm.label : '书签'}`)
                        .fontSize(13).fontColor('#333')
                        .layoutWeight(1)
                        .onClick(() => {
                          this.jumpToBookmark(bm.page);
                        })
                      Text('✕')
                        .fontSize(15).fontColor('#e57373')
                        .onClick(() => {
                          removeBookmark(getContext(this), bm.path, bm.page).then(() => {
                            this.loadBookmarks();
                          });
                        })
                    }
                    .width('100%')
                    .padding({ top: 6, bottom: 6 })
                  }
                }, (bm: Bookmark) => `${bm.page}_${bm.date}`)
              }
              .layoutWeight(1)
            }
          }
          .width('70%')
          .height('100%')
          .backgroundColor('#fff')
          .padding(12)
          .shadow({ radius: 8, color: '#40000000', offsetX: -2, offsetY: 0 })
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.End)
      }

      /* Hidden ArkWeb host exposing speechSynthesis for TTS (sprint D) */
      Web({ src: $rawfile('tts.html'), controller: this.webController })
        .width(1).height(1)
        .position({ x: -20, y: -20 })
        .opacity(0)
        .focusable(false)
        .javaScriptAccess(true)
        .onPageEnd(() => {
          this.probeTtsEngine();
        })
        .onErrorReceive((event: OnErrorReceiveEvent) => {
          this.ttsEngineOk = false;
          console.error('[Reader] TTS host page load error');
        })

      /* TTS reading panel (modal) */
      if (this.showTts) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.closeTtsPanel();
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('朗读 (TTS)')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(this.ttsStatus)
                .fontSize(13).fontColor(this.ttsEngineOk ? '#2a7' : '#e57373')

              if (!this.ttsEngineOk) {
                Text('当前设备未提供语音引擎，可改用速读模式。')
                  .fontSize(11).fontColor('#e57373').textAlign(TextAlign.Center)
              }

              Text(this.ttsSentences.length > 0 && this.ttsIndex < this.ttsSentences.length
                ? this.ttsSentences[this.ttsIndex] : '(本页无文本)')
                .fontSize(12).fontColor('#333').maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })

              Text(`第 ${Math.min(this.ttsIndex + 1, this.ttsSentences.length)}/${this.ttsSentences.length} 句 · 第 ${this.currentPage + 1} 页`)
                .fontSize(11).fontColor('#777')

              Row({ space: 10 }) {
                Button(this.ttsPaused ? '▶' : '⏸')
                  .fontSize(16).height(40).width(56)
                  .backgroundColor('#7b68ee').fontColor(Color.White)
                  .onClick(() => this.ttsTogglePause())
                Button('⏹')
                  .fontSize(16).height(40).width(56)
                  .backgroundColor('#e57373').fontColor(Color.White)
                  .onClick(() => this.ttsStop())
                Button('上一句')
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsPrevSentence())
                Button('下一句')
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsNextSentence())
              }

              Row({ space: 8 }) {
                Text('语速:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.ttsRate,
                  min: 0.5,
                  max: 2.0,
                  step: 0.1
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setTtsRate(value))
                Text(`${this.ttsRate.toFixed(1)}x`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Row({ space: 8 }) {
                Text('音调:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.ttsPitch,
                  min: 0.5,
                  max: 2.0,
                  step: 0.1
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setTtsPitch(value))
                Text(`${this.ttsPitch.toFixed(1)}x`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Row({ space: 8 }) {
                Button('⚡ 转速读')
                  .fontSize(12).height(36)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => {
                    this.closeTtsPanel();
                    this.openRsvpPanel();
                  })
                Button('关闭')
                  .fontSize(13).height(36)
                  .backgroundColor('#f0f0f0').fontColor('#555')
                  .onClick(() => this.closeTtsPanel())
              }
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('80%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }

      /* Speed reading RSVP panel (modal) */
      if (this.showRsvp) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.closeRsvpPanel();
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('速读 (RSVP)')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(`${this.rsvpWpm} 字/分`)
                .fontSize(13).fontColor('#e91e63')

              /* word flash area */
              Column() {
                Text(this.rsvpWord.length > 0 ? this.rsvpWord : '▶ 开始')
                  .fontSize(44).fontWeight(FontWeight.Bold)
                  .fontColor('#222')
                  .textAlign(TextAlign.Center)
                  .maxLines(1)
              }
              .width('100%').height(120)
              .justifyContent(FlexAlign.Center)
              .backgroundColor('#f5f5f5')
              .borderRadius(8)

              Text(this.rsvpProgress +
                (this.rsvpPaused ? ' (已暂停)' : (this.rsvpRunning ? '' : ' (已停止)')))
                .fontSize(11).fontColor('#777')

              Row({ space: 10 }) {
                Button(this.rsvpPaused ? '▶ 继续' : '▶ 开始')
                  .fontSize(13).height(40)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => this.rsvpToggle())
                Button('⏸ 暂停')
                  .fontSize(13).height(40)
                  .backgroundColor('#ff9800').fontColor(Color.White)
                  .onClick(() => this.rsvpPause())
                Button('⏹ 停止')
                  .fontSize(13).height(40)
                  .backgroundColor('#e57373').fontColor(Color.White)
                  .onClick(() => this.rsvpStop())
              }

              Row({ space: 8 }) {
                Text('速度:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.rsvpWpm,
                  min: 100,
                  max: 800,
                  step: 50
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setRsvpWpm(value))
                Text(`${this.rsvpWpm}`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Text('读完当前页自动进入下一页 · 手动翻页会重置到新页')
                .fontSize(11).fontColor('#999').textAlign(TextAlign.Center)

              Button('🔊 转朗读')
                .fontSize(12).height(36)
                .backgroundColor('#7b68ee').fontColor(Color.White)
                .onClick(() => {
                  this.closeRsvpPanel();
                  this.openTtsPanel();
                })

              Button('关闭')
                .fontSize(13).height(36)
                .backgroundColor('#f0f0f0').fontColor('#555')
                .onClick(() => this.closeRsvpPanel())
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('80%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }

      /* Settings panel (modal, sprint E) */
      if (this.showSettings) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showSettings = false;
              console.info('[Reader] Settings panel closed');
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('阅读设置')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Row({ space: 8 }) {
                Text('主题:')
                  .fontSize(13).fontColor('#555')
                ForEach(THEME_NAMES, (name: string, idx: number) => {
                  Button(name)
                    .fontSize(12).height(32)
                    .backgroundColor(this.theme === idx ? '#2a7' : '#e0e0e0')
                    .fontColor(this.theme === idx ? Color.White : '#333')
                    .onClick(() => this.setTheme(idx))
                }, (name: string, idx: number) => `t_${idx}`)
              }

              if (this.isReflow) {
                Row({ space: 8 }) {
                  Text('字号:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.fontSize, min: 10, max: 28, step: 1 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.fontSize = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.fontSize}`)
                    .fontSize(11).fontColor('#777').width(30)
                }

                Row({ space: 8 }) {
                  Text('行距:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.lineHeight, min: 1.0, max: 2.5, step: 0.1 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.lineHeight = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.lineHeight.toFixed(1)}`)
                    .fontSize(11).fontColor('#777').width(30)
                }

                Row({ space: 8 }) {
                  Text('页边距:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.pageMargin, min: 0, max: 80, step: 4 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.pageMargin = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.pageMargin}pt`)
                    .fontSize(11).fontColor('#777').width(36)
                }
              } else {
                Text('PDF 为固定版面：字号/行距/页边距不可调，使用 A-/A+ 缩放')
                  .fontSize(11).fontColor('#888').textAlign(TextAlign.Center)
              }

              Text('主题变更即时生效：深色/OLED 反色渲染，墨色为纸张暖色')
                .fontSize(11).fontColor('#999').textAlign(TextAlign.Center)

              Button('关闭')
                .fontSize(13).height(36)
                .backgroundColor('#f0f0f0').fontColor('#555')
                .onClick(() => {
                  this.showSettings = false;
                  console.info('[Reader] Settings panel closed');
                })
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('70%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }
    }
    .width('100%')
    .height('100%')
  }
}
