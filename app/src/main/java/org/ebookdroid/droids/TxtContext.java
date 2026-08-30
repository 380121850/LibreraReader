package org.ebookdroid.droids;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.TxtExtract;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.BookCSS;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.IOException;

public class TxtContext extends PdfContext {

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {

        String extractFile;
        try {
            if (AppState.get().isPreText) {
                extractFile = TxtExtract.extract(fileName, CacheZipUtils.CACHE_BOOK_DIR.getPath());
                return new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF, extractFile, "");
            }
            // Single-pass txt → EPUB with one spine chapter per detected
            // chapter heading (the old txt→fb2→epub chain did two full passes
            // and produced a single huge chapter).
            extractFile = TxtExtract.extractEpub(fileName, CacheZipUtils.CACHE_BOOK_DIR.getPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final MuPdfDocument muPdfDocument = new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF, extractFile, password);
        try {
            // Corruption probe: lay out only the first chapter (a full count
            // would force the whole-document layout and defeat fast-open).
            muPdfDocument.getPageCountProgressive(Dips.screenWidth(), Dips.screenHeight(),
                    BookCSS.get().fontSizeSp, 1);
        } catch (Exception e) {
            LOG.e(e);
        }

        return muPdfDocument;

    }
}
