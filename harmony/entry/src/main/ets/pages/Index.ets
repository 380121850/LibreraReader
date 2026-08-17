import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import { version, openDocument, pageCount, renderPage, getText, searchText, getDocumentInfo, RenderResult, TextRect, DocumentInfo } from 'libmupdf_napi.so';

@Entry
@Component
struct Index {
  @State title: string = 'Librera Reader';
  @State status: string = 'initializing...';
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State pageInfo: string = '';
  @State textResult: string = '';
  @State docInfo: string = '';
  @State currentPage: number = 0;

  aboutToAppear(): void {
    this.runRenderTest();
  }

  private async runRenderTest(): Promise<void> {
    let handle: ESObject = undefined;
    try {
      const ctx = getContext(this);
      const target = ctx.cacheDir + '/test.pdf';

      // extract rawfile into the sandbox so the native side can fopen() it
      const content: Uint8Array = await ctx.resourceManager.getRawFileContent('test.pdf');
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content.buffer);
      fs.closeSync(file);

      const mupdfVersion: string = version();
      handle = openDocument(target);
      const pages: number = pageCount(handle);

      // render page 0
      const res: RenderResult = renderPage(handle, 0, 2.0);
      this.currentPage = 0;

      const opts: image.InitializationOptions = {
        size: { width: res.width, height: res.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(res.data, opts);

      // extract text from page 0
      try {
        const text: string = getText(handle, 0, 1.0);
        this.textResult = text.length > 500 ? text.substring(0, 500) + '...' : text;
      } catch (e) {
        this.textResult = 'text: (N/A)';
      }

      // get document info
      try {
        const info: DocumentInfo = getDocumentInfo(handle);
        const parts: string[] = [];
        if (info.title) parts.push(`title=${info.title}`);
        if (info.author) parts.push(`author=${info.author}`);
        if (info.creator) parts.push(`creator=${info.creator}`);
        if (info.producer) parts.push(`producer=${info.producer}`);
        this.docInfo = parts.length > 0 ? parts.join(' | ') : 'no metadata';
      } catch (e) {
        this.docInfo = 'no metadata';
      }

      // search text
      try {
        const rects: TextRect[] = searchText(handle, 'PDF', 0);
        if (rects.length > 0) {
          this.pageInfo = `found ${rects.length} "PDF" on page 1`;
        } else {
          this.pageInfo = `page 1 | ${rects.length} matches`;
        }
      } catch (e) {
        this.pageInfo = 'page 1 | search N/A';
      }

      this.status = `mupdf ${mupdfVersion} | pages=${pages} | ${res.width}x${res.height}`;
    } catch (e) {
      this.status = 'error: ' + JSON.stringify(e);
    }
  }

  build() {
    Column({ space: 16 }) {
      Text(this.title)
        .fontSize(28)
        .fontWeight(FontWeight.Bold)

      Text(this.status)
        .fontSize(14)
        .fontColor(Color.Gray)
        .textAlign(TextAlign.Center)
        .padding({ left: 16, right: 16 })

      if (this.pixelMap) {
        Image(this.pixelMap)
          .objectFit(ImageFit.Contain)
          .width('92%')
          .borderRadius(8)
      }

      Text(this.pageInfo)
        .fontSize(12)
        .fontColor(Color.Blue)
        .textAlign(TextAlign.Center)
        .margin({ top: 8 })

      Column() {
        Text(this.textResult)
          .fontSize(10)
          .fontColor('#666666')
          .textAlign(TextAlign.Start)
          .maxLines(4)
          .textOverflow({ overflow: TextOverflow.Clip })
          .width('92%')
      }
      .padding(8)
      .backgroundColor(Color.White)
      .borderRadius(8)
      .width('92%')
      .margin({ top: 4 })

      Text(this.docInfo)
        .fontSize(10)
        .fontColor(Color.Green)
        .textAlign(TextAlign.Center)
        .margin({ top: 4 })

      // Entry button to the Reader (Swiper paging UI)
      Button('打开阅读器 (Reader)')
        .fontSize(16)
        .fontColor(Color.White)
        .backgroundColor(Color.Blue)
        .width('70%')
        .height(44)
        .margin({ top: 16 })
        .onClick(() => {
          router.pushUrl({ url: 'components/Reader' })
            .then(() => {
              console.info('[Index] Navigated to Reader');
            })
            .catch((err: Error) => {
              console.error('[Index] Navigate failed: ' + JSON.stringify(err));
            });
        })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .padding(16)
  }
}
