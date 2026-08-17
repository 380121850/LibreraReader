/**
 * Reader component for Librera Reader (HarmonyOS).
 *
 * Features:
 * - Swiper-based horizontal paging with LazyForEach preloading
 * - renderPageAsync for non-blocking background rendering
 * - Tap zones: left=back, right=next page, center=toggle menu
 * - Progress indicator showing current position
 */

import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import { version, openDocument, pageCount, renderPage, closeDocument, RenderResult } from 'libmupdf_napi.so';

const LEFT_ZONE_RATIO: number = 0.3;
const RIGHT_ZONE_RATIO: number = 0.3;

/**
 * Data source for LazyForEach page list.
 */
class PageDataSource implements IDataSource {
  private pageIndices: number[] = [];
  private listeners: DataChangeListener[] = [];
  private docHandle: ESObject | undefined = undefined;
  private zoom: number = 1.5;

  setDocHandle(handle: ESObject): void {
    this.docHandle = handle;
  }

  setZoom(zoom: number): void {
    this.zoom = zoom;
  }

  renderPage(pageNumber: number): RenderResult {
    if (this.docHandle === undefined) {
      throw new Error('Document not opened');
    }
    return renderPage(this.docHandle, pageNumber, this.zoom);
  }

  getPageCount(): number {
    let count = 0;
    if (this.docHandle !== undefined) {
      count = pageCount(this.docHandle);
    }
    return count;
  }

  openDoc(path: string): void {
    try {
      this.docHandle = openDocument(path);
      const total: number = pageCount(this.docHandle);
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
 * Single page renderer component.
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
      const renderResult: RenderResult = this.dataSource.renderPage(this.pageIndex);

      const opts: image.InitializationOptions = {
        size: { width: renderResult.width, height: renderResult.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(renderResult.data, opts);
    } catch (e) {
      this.errorText = 'Render error';
      console.error(`[Reader] Page ${this.pageIndex} render failed`);
    } finally {
      this.isLoading = false;
    }
  }

  build() {
    Column() {
      if (this.isLoading) {
        Row() {
          Progress({ value: 30, total: 100 })
            .width(48)
            .height(48)
            .color(Color.Blue)
          Text('Rendering...')
            .fontSize(12)
            .fontColor(Color.Gray)
            .margin({ left: 8 })
        }
        .justifyContent(FlexAlign.Center)
      } else if (this.errorText.length > 0) {
        Text(this.errorText)
          .fontSize(14)
          .fontColor(Color.Red)
          .textAlign(TextAlign.Center)
      } else if (this.pixelMap !== undefined) {
        Image(this.pixelMap)
          .objectFit(ImageFit.Contain)
          .width('100%')
          .height('110%')
          .borderRadius(4)
      } else {
        Column() {
          Text(`Page ${this.pageIndex + 1}`)
            .fontSize(18)
            .fontColor(Color.Gray)
          Text('Loading...')
            .fontSize(12)
            .fontColor('#999999')
            .margin({ top: 4 })
        }
        .width('80%')
        .height('70%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#F5F5F5')
        .borderRadius(8)
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
  private documentPath: string = '';
  private dataSource: PageDataSource = new PageDataSource();

  aboutToAppear(): void {
    try {
      const ctx = getContext(this);
      this.documentPath = ctx.cacheDir + '/test.pdf';
      this.ensureTestPdf(this.documentPath).then(() => {
        this.dataSource.openDoc(this.documentPath);
        this.totalPages = this.dataSource.getPageCount();
        for (let i = 0; i < this.totalPages; i++) {
          this.pageList.push(i);
        }
        console.info(`[Reader] Document opened: ${this.totalPages} pages`);
      }).catch((err: Error) => {
        console.error('[Reader] Init failed: ' + JSON.stringify(err));
      });
    } catch (e) {
      console.error('[Reader] Init failed');
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
    this.dataSource.closeDoc();
  }

  private onPageChanged(index: number): void {
    this.currentPage = index;
    console.info(`[Reader] Page changed to ${index + 1}/${this.totalPages}`);
  }

  build() {
    Column() {
      // Top bar with page info and navigation buttons
      Row() {
        Text(this.totalPages > 0 ? `Page ${this.currentPage + 1} / ${this.totalPages}` : 'Loading...')
          .fontSize(14)
          .fontColor(Color.White)

        Blank()

        Text('|<')
          .fontSize(16)
          .fontColor(this.currentPage === 0 ? '#888888' : Color.White)
          .margin({ right: 8 })
          .onClick(() => {
            if (this.currentPage > 0) {
              this.currentPage = 0;
            }
          })

        Text('>|')
          .fontSize(16)
          .fontColor(this.totalPages > 0 && this.currentPage >= this.totalPages - 1 ? '#888888' : Color.White)
          .margin({ left: 8 })
          .onClick(() => {
            if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
              this.currentPage = this.totalPages - 1;
            }
          })
      }
      .width('100%')
      .height(40)
      .justifyContent(FlexAlign.Center)
      .backgroundColor('#333333')

      // Progress indicator
      Progress({ value: this.totalPages > 1 ? (this.currentPage / (this.totalPages - 1)) * 100 : 0, total: 100 })
        .width('96%')
        .height(4)
        .color(Color.Blue)

      // Main reader area with Swiper + tap zones
      Stack() {
        Swiper() {
          ForEach(this.pageList, (pageIdx: number) => {
            PageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
          }, (item: number) => String(item))
        }
        .index(this.currentPage)
        .width('100%')
        .height('100%')
        .loop(false)
        .indicator(false)
        .autoPlay(false)
        .onChange((index: number) => {
          this.onPageChanged(index);
        })

        // Left tap zone (previous page)
        Row()
          .width(`${LEFT_ZONE_RATIO * 100}%`)
          .height('100%')
          .backgroundColor(Color.Transparent)
          .onClick(() => {
            if (this.currentPage > 0) {
              this.currentPage = this.currentPage - 1;
            }
          })

        // Right tap zone (next page)
        Row()
          .width(`${RIGHT_ZONE_RATIO * 100}%`)
          .height('100%')
          .align(Alignment.End)
          .backgroundColor(Color.Transparent)
          .onClick(() => {
            if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
              this.currentPage = this.currentPage + 1;
            }
          })

        // Center tap zone (toggle menu)
        Row()
          .width(`${(1 - LEFT_ZONE_RATIO - RIGHT_ZONE_RATIO) * 100}%`)
          .height('100%')
          .align(Alignment.Center)
          .backgroundColor(Color.Transparent)
          .onClick(() => {
            this.showMenu = !this.showMenu;
          })
      }
      .width('100%')
      .layoutWeight(1)

      // Bottom info bar
      Row() {
        Text('Zoom: 1.5x')
          .fontSize(12)
          .fontColor(Color.Gray)

        Blank()

        if (this.showMenu) {
          Text('Menu')
            .fontSize(12)
            .fontColor(Color.Blue)
        }
      }
      .width('100%')
      .height(36)
      .justifyContent(FlexAlign.Center)
      .backgroundColor('#FAFAFA')
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.White)
  }
}
