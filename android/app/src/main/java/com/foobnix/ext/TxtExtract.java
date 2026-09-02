package com.foobnix.ext;

import android.text.TextUtils;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.hypen.HypenUtils;
import com.foobnix.model.AppData;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.model.BookCSS;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;

public class TxtExtract {

    public static final String OUT_FB2_XML = "txt.html";

    static char[] endChars = new char[]{'.', '!', '?', ';'};

    public static String foramtUB(String line) {
        if (line != null && line.trim()
                                .startsWith("(*)") && TxtUtils.isLastCharEq(line, endChars)) {
            line = "<b><u>" + line + "</u></b>";
        }
        return line;
    }

    public static String extract1(String inputPath, String outputDir) throws IOException {
        String encoding = "UTF-8";
        if (AppState.get().isCharacterEncoding) {
            encoding = AppState.get().characterEncoding;
        } else {
            encoding = ExtUtils.determineTxtEncoding(new FileInputStream(inputPath));
        }

        // The conversion is O(file size) (full read + optional hyphenation), so
        // cache it per source path + conversion settings and only regenerate
        // when the cached file is missing.
        final String key = inputPath.hashCode() + "_" + BookCSS.get().isAutoHypens + AppSP.get().hypenLang
                + encoding + AppState.get().isCharacterEncoding;
        File file = new File(outputDir, key.hashCode() + "_.fb2");
        if (file.isFile() && file.length() > 0) {
            LOG.d("extract1 cache", file);
            return file.getPath();
        }
        File tmp = new File(outputDir, file.getName() + ".tmp");

        BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(inputPath), encoding));

        PrintWriter writer = new PrintWriter(tmp);
        String line;
        writer.println("<FictionBook>");

        if (BookCSS.get().isAutoHypens) {
            HypenUtils.applyLanguage(AppSP.get().hypenLang);
        }

        while ((line = input.readLine()) != null) {
            line = TextUtils.htmlEncode(line);
            String trimLine = line.toLowerCase();
            if (line.isEmpty()) {
                continue;
            }
            if (trimLine.startsWith("chapter ") || trimLine.startsWith("глава ") || trimLine.startsWith(
                    "часть ") || trimLine.startsWith("розділ ") ||
                    //trimLine.startsWith("#") ||
                    //trimLine.startsWith("*") ||
                    line.matches("[A-ZА-Я &()_:-]*")) {
                LOG.d("MATCH", line);
                writer.println("<section><title>");
                writer.println(line);
                writer.println("</title></section>");
            } else {
                if (BookCSS.get().isAutoHypens && TxtUtils.isNotEmpty(AppSP.get().hypenLang)) {
                    line = HypenUtils.applyHypnes(line);
                }
                writer.println("<p>" + line + "</p>");
            }
        }
        writer.println("</FictionBook>");
        input.close();
        writer.close();
        tmp.renameTo(file);
        return file.getPath();
    }

    private static final Pattern CN_CHAPTER = Pattern.compile("第[0-9０-９一二三四五六七八九十百千万零两]+[章節节回卷部篇集].*");
    private static final String[] CN_CHAPTER_PREFIX = {"序章", "楔子", "尾声", "結尾", "结尾", "番外", "前言", "引言", "后记", "後記", "结语", "結語"};

    private static boolean isChapterStart(final String rawLine, final String lowerLine) {
        if (lowerLine.startsWith("chapter ") || lowerLine.startsWith("глава ") || lowerLine.startsWith("часть ")
                || lowerLine.startsWith("розділ ")) {
            return true;
        }
        if (rawLine.length() <= 50) {
            if (CN_CHAPTER.matcher(rawLine).matches()) {
                return true;
            }
            for (String prefix : CN_CHAPTER_PREFIX) {
                if (lowerLine.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Single-pass txt → synthetic EPUB conversion. Replaces the old
     * txt→fb2→epub chain (two full passes over the file) and produces one
     * spine chapter per detected chapter heading (including Chinese 第N章
     * style titles), so MuPDF can lay out and render chapter by chapter.
     */
    public static String extractEpub(String inputPath, String outputDir) throws IOException {
        String encoding = "UTF-8";
        if (AppState.get().isCharacterEncoding) {
            encoding = AppState.get().characterEncoding;
        } else {
            encoding = ExtUtils.determineTxtEncoding(new FileInputStream(inputPath));
        }

        final String key = inputPath.hashCode() + "_" + BookCSS.get().isAutoHypens + AppSP.get().hypenLang
                + encoding + AppState.get().isCharacterEncoding + "_epubv2";
        File epubFile = new File(outputDir, key.hashCode() + "_.epub");
        if (epubFile.isFile() && epubFile.length() > 0) {
            LOG.d("extractEpub cache", epubFile);
            return epubFile.getPath();
        }
        File tmp = new File(outputDir, epubFile.getName() + ".tmp");

        final BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(inputPath), encoding));
        final boolean hyphens = BookCSS.get().isAutoHypens;
        if (hyphens) {
            HypenUtils.applyLanguage(AppSP.get().hypenLang);
        }

        final ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024));

        final List<String> chapters = new ArrayList<String>();
        final StringBuilder body = new StringBuilder(64 * 1024);
        String currentTitle = "";
        int chapterIndex = 0;

        final CRC32 crc = new CRC32();
        final byte[] mimetype = "application/epub+zip".getBytes("utf-8");
        crc.update(mimetype);
        final ZipEntry me = new ZipEntry("mimetype");
        me.setMethod(ZipEntry.STORED);
        me.setSize(mimetype.length);
        me.setCrc(crc.getValue());
        zip.putNextEntry(me);
        zip.write(mimetype);

        zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
        zip.write(("<?xml version=\"1.0\"?><container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles></container>").getBytes("utf-8"));
        zip.closeEntry();

        String line;
        while ((line = input.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (chapterIndex == 0 || isChapterStart(line, line.toLowerCase())) {
                if (chapterIndex > 0) {
                    writeChapterZipEntry(zip, chapterIndex, currentTitle, body);
                }
                chapterIndex++;
                currentTitle = line.trim();
                chapters.add(currentTitle);
                body.setLength(0);
                continue;
            }
            String safe = TextUtils.htmlEncode(line);
            if (hyphens && TxtUtils.isNotEmpty(AppSP.get().hypenLang)) {
                safe = HypenUtils.applyHypnes(safe);
            }
            body.append("<p>").append(safe).append("</p>");
        }
        if (chapterIndex == 0) {
            // No chapter markers at all: the whole book is one chapter.
            chapterIndex = 1;
            chapters.add(ExtUtils.getFileName(inputPath));
        }
        writeChapterZipEntry(zip, chapterIndex, currentTitle, body);
        input.close();

        final StringBuilder opf = new StringBuilder(4096);
        final StringBuilder ncx = new StringBuilder(4096);
        opf.append("<?xml version=\"1.0\" encoding=\"utf-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" ")
           .append("version=\"2.0\" unique-identifier=\"bid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
           .append("<dc:title>").append(TextUtils.htmlEncode(ExtUtils.getFileName(inputPath)))
           .append("</dc:title><dc:language>zh</dc:language>")
           .append("<dc:identifier id=\"bid\">txt-").append(inputPath.hashCode()).append("</dc:identifier>")
           .append("</metadata><manifest>");
        ncx.append("<?xml version=\"1.0\" encoding=\"utf-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" ")
           .append("version=\"2005-1\"><head/><docTitle><text>Bench</text></docTitle><navMap>");
        for (int i = 1; i <= chapters.size(); i++) {
            opf.append("<item id=\"c").append(i).append("\" href=\"ch").append(i)
               .append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
            ncx.append("<navPoint id=\"n").append(i).append("\" playOrder=\"").append(i)
               .append("\"><navLabel><text>").append(TextUtils.htmlEncode(chapters.get(i - 1)))
               .append("</text></navLabel><content src=\"ch").append(i).append(".xhtml\"/></navPoint>");
        }
        opf.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/></manifest><spine toc=\"ncx\">");
        for (int i = 1; i <= chapters.size(); i++) {
            opf.append("<itemref idref=\"c").append(i).append("\"/>");
        }
        opf.append("</spine></package>");
        ncx.append("</navMap></ncx>");

        zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
        zip.write(opf.toString().getBytes("utf-8"));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
        zip.write(ncx.toString().getBytes("utf-8"));
        zip.closeEntry();

        zip.close();
        tmp.renameTo(epubFile);
        LOG.d("extractEpub done", epubFile, chapters.size() + " chapters");
        return epubFile.getPath();
    }

    private static void writeChapterZipEntry(final ZipOutputStream zip, final int index, final String title,
            final StringBuilder body) throws IOException {
        zip.putNextEntry(new ZipEntry("OEBPS/ch" + index + ".xhtml"));
        final StringBuilder html = new StringBuilder(body.length() + 256);
        html.append("<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
            .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>")
            .append("<title>").append(TextUtils.htmlEncode(title)).append("</title></head><body>");
        if (TxtUtils.isNotEmpty(title)) {
            html.append("<h2>").append(TextUtils.htmlEncode(title)).append("</h2>");
        }
        html.append(body).append("</body></html>");
        zip.write(html.toString().getBytes("utf-8"));
        zip.closeEntry();
    }

    public static String extract(String inputPath, String outputDir) throws IOException {
        File file = new File(outputDir, AppState.get().isPreText + OUT_FB2_XML);

        boolean isJSON = inputPath.endsWith(".json");

        String encoding = "UTF-8";
        if (AppState.get().isCharacterEncoding) {
            encoding = AppState.get().characterEncoding;
        } else {
            encoding = ExtUtils.determineTxtEncoding(new FileInputStream(inputPath));
        }

        BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(inputPath), encoding));
        PrintWriter writer = new PrintWriter(file);
        String line;

        writer.println("<!DOCTYPE html>");
        writer.println("<html>");
        if (AppState.get().isPreText) {
            writer.println(
                    "<head><style>@page{margin:0px 0.5em} pre{margin:0px;white-space:pre !important;} {body:margin:0px}</style></head>");
        } else {
            writer.println("<head><style>p,p+p{margin:0}</style></head>");
        }
        writer.println("<body>");

        if (AppState.get().isPreText) {
            writer.println("<pre>");
        }

        if (AppState.get().isLineBreaksText) {
            writer.println("<p>");
        }

        if (BookCSS.get().isAutoHypens) {
            HypenUtils.applyLanguage(AppSP.get().hypenLang);
        }

        List<SimpleMeta> replacements = AppData.get()
                                               .getAllTextReplaces();

        while ((line = input.readLine()) != null) {
            String outLn = null;

            if (AppState.get().isPreText) {

                outLn = retab(line, 8);
                outLn = TextUtils.htmlEncode(outLn);

                if (TxtUtils.isLineStartEndUpperCase(outLn)) {
                    outLn = "<b>" + outLn + "</b>";
                }

            } else {

                if (AppState.get().isLineBreaksText) {
                    if (line.trim()
                            .length() == 0) {
                        outLn = "<br/>";
                    } else {
                        outLn = format(line, replacements);
                    }

                } else {
                    if (line.trim()
                            .length() == 0) {
                        outLn = "<p>&nbsp;</p>";
                    } else if (TxtUtils.isLineStartEndUpperCase(line)) {
                        outLn = "<b>" + format(line, replacements) + "</b>";
                    } else if (line.contains("Title:")) {
                        outLn = "<b>" + format(line, replacements) + "</b>";
                    } else {
                        outLn = "<p>" + format(line, replacements) + "</p>";
                    }
                }

            }
            if (isJSON) {
                outLn = outLn.replace(",", ",<br/>");
            }

            outLn = Fb2Extractor.accurateLine(outLn);
            LOG.d("LINE", outLn);

            writer.println(outLn);
        }
        if (AppState.get().isLineBreaksText) {
            writer.println("</p>");
        }

        if (AppState.get().isPreText) {
            writer.println("</pre>");
        }
        writer.println("</body></html>");

        input.close();
        writer.close();

        return file.getPath();
    }

    public static String retab(final String text, final int tabstop) {
        final char[] input = text.toCharArray();
        final StringBuilder sb = new StringBuilder();

        int linepos = 0;
        for (int i = 0; i < input.length; i++) {
            // treat the character
            final char ch = input[i];
            if (ch == '\t') {
                // expand the tab
                do {
                    sb.append(' ');
                    linepos++;
                } while (linepos % tabstop != 0);
            } else {
                sb.append(ch);
                linepos++;
            }

            // end of line. Reset the lineposition to zero.
            // if (ch == '\n' || ch == '\r' || (ch | 1) == '\u2029' || ch ==
            // '\u0085')
            // linepos = 0;

        }

        return sb.toString();
    }

    public static String format(String line, List<SimpleMeta> replacements) {
        try {
            line = line.replace("\n", "");
            line = line.replace("\r", "");
            line = TextUtils.htmlEncode(line);
            if (BookCSS.get().isAutoHypens && TxtUtils.isNotEmpty(AppSP.get().hypenLang)) {
                line = HypenUtils.applyHypnes(line, replacements);
            }
            line = line.trim();

        } catch (Exception e) {
            LOG.e(e);
        }
        return line;
    }

}
