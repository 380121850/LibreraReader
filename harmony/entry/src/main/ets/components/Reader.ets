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
 */

import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import { version, openDocument, pageCount, renderPageAsync, closeDocument, getToc, RenderResult, RenderOptions, TocEntry } from 'libmupdf_napi.so';
import { saveRecentBook, getPageForPath, RecentBook } from '../model/ReadingProgress';

const LEFT_ZONE_RATIO: number = 0.3;
const RIGHT_ZONE_RATIO: number = 0.3;
const MIN_ZOOM: number = 0.5;
const MAX_ZOOM: number = 4.0;
const ZOOM_STEP: number = 0.25;

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

  async renderPage(pageNumber: number): Promise<RenderResult> {
    if (this.docHandle === undefined) {
      if (this.docPath.length > 0) {
        console.info('[Reader] Document handle lost, re-opening');
        this.openDoc(this.docPath);
      } else {
        throw new Error('Document not opened');
      }
    }
    const opts: RenderOptions = { zoom: this.zoom, invert: this.invert };
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
 * Single page renderer (used in both Swiper and List modes).
 */
@Component
struct PageRenderer {
  pageIndex: number = 0;
  dataSource: PageDataSource = new PageDataSource();
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State isLoading: boolean = true;
  @State errorText: string = '';

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
      const renderResult: RenderResult = await this.dataSource.renderPage(this.pageIndex);
      const opts: image.InitializationOptions = {
        size: { width: renderResult.width, height: renderResult.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(renderResult.data, opts);
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
        Image(this.pixelMap)
          .objectFit(ImageFit.Contain)
          .width('100%')
          .borderRadius(2)
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
  @State zoomLevel: number = 1.5;
  @State nightMode: boolean = false;
  @State showToc: boolean = false;
  @State tocEntries: TocEntry[] = [];
  private documentPath: string = '';
  private dataSource: PageDataSource = new PageDataSource();

  aboutToAppear(): void {
    try {
      const ctx = getContext(this);
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
    } catch (e) {
      console.error('[Reader] Init failed');
    }
  }

  private initReader(): void {
    try {
      this.dataSource.openDoc(this.documentPath);
      this.totalPages = this.dataSource.getPageCount();
      for (let i = 0; i < this.totalPages; i++) {
        this.pageList.push(i);
      }
      /* Load TOC in background */
      this.loadToc();
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
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: this.currentPage,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString()
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
  }

  private saveProgress(page: number): void {
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: page,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString()
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
    this.dataSource.setInvert(this.nightMode);
    console.info(`[Reader] Night mode: ${this.nightMode ? 'ON' : 'OFF'}`);
  }

  private toggleScrollMode(): void {
    this.scrollMode = this.scrollMode === 0 ? 1 : 0;
    console.info(`[Reader] Scroll mode: ${this.scrollMode === 0 ? 'horizontal' : 'vertical'}`);
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      /* Main content area */
      Column() {
        // Top bar
        Row() {
          Text(this.docTitle.length > 12 ? this.docTitle.substring(0, 12) + '…' : (this.docTitle.length > 0 ? this.docTitle : ''))
            .fontSize(13).fontColor('#ccc').maxLines(1)

          Text(this.totalPages > 0 ? ` ${this.currentPage + 1}/${this.totalPages}` : 'Loading...')
            .fontSize(14).fontColor(Color.White)

          Blank()

          // Mode indicator
          Text(this.scrollMode === 0 ? '⇄' : '⇅')
            .fontSize(16).fontColor('#aaa').margin({ right: 8 })
        }
        .width('100%').height(40)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(this.nightMode ? '#1a1a1a' : '#333333')

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
                PageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
              }, (item: number) => `${item}_${this.zoomLevel}_${this.nightMode}`)
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
            }, (item: number) => `v_${item}_${this.zoomLevel}_${this.nightMode}`)
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
        .backgroundColor(this.nightMode ? '#1a1a1a' : '#f5f5f5')
      }
      .width('100%').height('100%')

      /* Menu overlay (center panel) */
      if (this.showMenu) {
        Column({ space: 16 }) {
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
        .width('70%')
        .padding(20)
        .backgroundColor(Color.White)
        .borderRadius(12)
        .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        .position({ x: '15%', y: '30%' })
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
    }
    .width('100%')
    .height('100%')
  }
}
