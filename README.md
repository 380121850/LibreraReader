![Logo](https://github.com/380121850/howread/blob/master/logo.jpg)


# HowRead · 好好读

**This is a fork of [Librera Reader](https://github.com/foobnix/LibreraReader).**

> Original Librera Reader is Copyright (c) Ivan Oleksandrovych Ivanenko.
> This project is an independent fork, not affiliated with or endorsed by the original author.

- Original License: GPL-3.0-or-later
- This fork License: GPL-3.0-or-later
- Uses MuPDF under AGPL-3.0

## Changes from upstream
- 调整了UI布局，去掉了很多配置参数，更加专注阅读本身
- 增加了WebDAV文件浏览、以及WebDAV同步支持，降低对商用网盘的依赖
- 增加了AI大模型接入（兼容openAI标准，由用户自己配置AI KEY)，阅读时可以随时与AI交互
- 增加了笔记功能，并且阅读时与AI交互内容，可以方便的一键加到笔记中
- 增加了阅读统计，可以记录阅读时间、速度等信息
- 做了性能优化，懒加载 + 并行模式，优化电子书文件打开速率
- 与原功能相比变更较大，APP改名为HowRead (好好读)，意在于：值得读的，好好读
- TODO

**The development and support of Librera is frozen for an unpredictable time, there is a big war in my country
Ukraine.**
[Russian invasion of Ukraine](https://en.wikipedia.org/wiki/2022_Russian_invasion_of_Ukraine)

🇺🇦 To help Ukraine, please donate to these funds 💙💛

[OFFICIAL FUNDRAISING PLATFORM OF UKRAINE](https://u24.gov.ua/)

[Повернись Живим - Come Back Alive](https://savelife.in.ua/en/)

[Фонд Сергія Стерненка - Foundation Sternenko Community](https://www.sternenkofund.org/en)

[Фонд Сергія Притули - Serhiy Prytula Charity Foundation](https://prytulafoundation.org/en/)

# Librera Reader

Librera Reader is an e-book reader for Android devices;
it supports the following formats: PDF, EPUB, EPUB3, MOBI, DjVu, FB2, TXT, RTF, AZW, AZW3, HTML, CBZ, CBR, DOC, DOCX,
and OPDS Catalogs

# Download application

[Librera Reader on Google Play](https://play.google.com/store/apps/details?id=com.foobnix.pdf.reader)

[Librera PRO on Google Play](https://play.google.com/store/apps/details?id=com.foobnix.pro.pdf.reader)

[Librera F-Droid](https://f-droid.org/en/packages/com.foobnix.pro.pdf.reader/)

[Beta testing .apk](http://beta.librera.mobi/)

[Application Fonts.zip](https://github.com/foobnix/LirbiReader/tree/master/Builder/fonts)


[Web browser Librera Book Reader](https://librera.mobi/online-book-reader/)

https://librera.mobi/online-book-reader/?file=https://pdfobject.com/pdf/sample.pdf

[Google Chrome Extension](https://chromewebstore.google.com/detail/epub-reader-librera/kfpiokccdkdlbjmgiajpfcdefcbdbphe)

[zip Chrome Extension](https://github.com/foobnix/LibreraReader/raw/master/Builder/librara-chrome-extension.zip)

[MacOS DMG / Desktop version](http://beta.librera.mobi/)

Librera for macOS, supports PDF, EPUB, FB2, CBZ, CBR (beta.librera.mobi for downloads)

![img1](https://raw.githubusercontent.com/foobnix/LirbiReader/master/iosApp/img1.png)

![img2](https://raw.githubusercontent.com/foobnix/LirbiReader/master/iosApp/img2.png)

### Links

[web: https://librera.mobi/](https://librera.mobi/)

[What is new/Changes](https://librera.mobi/what-is-new/)

[FAQ](https://librera.mobi/faq/)

[Telegram Info](https://t.me/LibreraReader)

[Telegram Chat](https://t.me/librera_reader_chat)

♥︎♥︎♥︎ [Support & Donation on Patreon](https://www.patreon.com/librera) ♥︎♥︎♥︎

[Email: librera.reader@gmail.com](mailto:librera.reader@gmail.com)

## Required build libs

~~~~
mesa-common-dev libxcursor-dev libxrandr-dev libxinerama-dev libglu1-mesa-dev libxi-dev pkg-config libgl-dev
~~~~

You also need the Android NDK in version 20+
Please ensure to download it using android studio and add the NDK to your PATH.

## Create a keystore

Even if you do not plan to upload a version yourself you need a keystore with a certificate to build.
The keystore needs to be in PKCS12 format.
You can create a keystore in your actual directory using the following call
(replace ALIAS by your alias, it is just a name):

~~~~
keytool -genkey -v -storetype PKCS12 -keystore keystore.pkcs12 -alias ALIAS -keyalg RSA -keysize 2048 -validity 10000
~~~~

Now edit or create the file ~/.gradle/gradle.properties and set following values
(replacing PASSWD by the password you typed while creating the keystore, ALIAS as before and using the path to your
keystore):

~~~~
RELEASE_STORE_FILE=/PATH/TO/YOUR/keystore.pkcs12
RELEASE_STORE_PASSWORD=PASSWD
RELEASE_KEY_PASSWORD=PASSWD
RELEASE_KEY_ALIAS=ALIAS
~~~~

## Create Firebase Authentication file

To build with firebase support (all version but the ones for Fdroid) you need to get an
authentication file for firebase services offered by google. Therefore please follow
https://firebase.google.com/docs/android/setup to create your own project. You need to
register for the packages com.foobnix.pdf.info and com.foobnix.pdf.reader.a1. This way
you will get a google-services.json file that you have to place in the app folder of
the repository.

For this project only Analytics is used, so a spakling plan is all you need.

## Librera Build on MuPdf

~~~~
cd Builder
./link_to_mupdf_x.x.x.sh (Change the paths to mupdf and jniLibs folders)
cd ..
./gradlew assembleLibrera
~~~~

## Building for F-Droid for Android

If you wish to build for F-Droid (e.g. not using google services, Internet) you can run the build with

~~~~
cd Builder
./link_to_mupdf_x.x.x.sh
cd ..
./gradlew assembleFdroid
~~~~

F-Droid build does also not need a **google-services.json**

## Librera depends on:

MuPDF - (AGPL License) https://github.com/ArtifexSoftware/mupdf

* ebookdroid
* djvulibre
* hpx
* junrar
* glide
* libmobi
* commons-compress
* eventbus
* greendao
* jsoup
* juniversalchardet
* commons-compress
* okhttp3
* okhttp-digest
* okio
* rtfparserkit
* java-mammoth
* zip4j

Librera is distributed under the GPL

## License

See the [LICENSE](LICENSE.txt) file for license rights and limitations (GPL v.3).
