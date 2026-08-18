/*
 * MuPDF NAPI bindings for HarmonyOS.
 *
 * Adapted for MuPDF 1.23.7 API and OHOS NAPI:
 *   version()                      -> FZ_VERSION macro
 *   openDocument(path)             -> fz_open_document
 *   openDocumentByFd(fd)           -> fz_open_file_ptr_no_close
 *   pageCount(handle)              -> fz_count_pages
 *   renderPage(handle, no, zoom)   -> RGBA pixels as ArrayBuffer
 *   renderPageAsync(handle, no, o) -> Promise<RenderResult>, napi_async_worker
 *   getToc(handle)                 -> flat outline list with depth markers
 *   getPageSize(handle, page)      -> native media size in points (0 deg)
 *   getText(handle, page, zoom)    -> text content as UTF-8 string
 *   searchText(handle, text, page) -> array of {x0, y0, x1, y1} rects
 *   getDocumentInfo(handle)        -> object with title/author/creator etc.
 *   closeDocument(handle)          -> explicit drop (finalize also guards)
 *
 * Threading & lifetime:
 * - MuPDF contexts are not thread-safe. All native access is serialized
 *   through g_mu; the async render worker holds it for its whole job.
 * - DocumentHandle is reference-counted: the napi_external holds one
 *   reference, each queued RenderJob holds one. closeDocument only drops
 *   the MuPDF document and marks the handle closed (tombstone); the
 *   context and the handle itself are freed exactly once, when the last
 *   reference is released (GC finalizer or async-job completion).
 * - Every fz_try block follows MuPDF's fz_var() discipline: locals that
 *   are written inside the try and read after a potential longjmp are
 *   protected, so optimized (release -O2) builds stay well-defined.
 */
#include "napi/native_api.h"
#include "mupdf/fitz.h"
#include "mupdf/pdf.h"

#include <atomic>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <pthread.h>
#include <string>
#include <utility>
#include <vector>

namespace {

/* PATH_MAX is not reliably exposed by the OHOS musl headers */
constexpr size_t kMaxPath = 4096;

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

struct DocumentHandle {
    fz_context *ctx;          /* valid until the last reference is released */
    fz_document *doc;         /* nullptr once closed */
    std::atomic<int> refs;    /* 1 (napi_external) + N in-flight render jobs */
    std::atomic<bool> closed; /* set by closeDocument / finalizer */
};

static void AcquireHandle(DocumentHandle *h)
{
    h->refs.fetch_add(1);
}

/* Drop one reference; on the last reference free the MuPDF resources and
 * the handle itself. This is the ONLY place that deletes a DocumentHandle,
 * so a napi_external can never wrap freed memory after an explicit close. */
static void ReleaseHandle(DocumentHandle *h)
{
    if (h->refs.fetch_sub(1) != 1) {
        return;
    }
    pthread_mutex_lock(&g_mu);
    if (h->doc != nullptr) {
        fz_drop_document(h->ctx, h->doc);
        h->doc = nullptr;
    }
    fz_context *ctx = h->ctx;
    h->ctx = nullptr;
    h->closed = true;
    pthread_mutex_unlock(&g_mu);
    if (ctx != nullptr) {
        fz_drop_context(ctx);
    }
    delete h;
}

void FinalizeDocument(napi_env /*env*/, void *data, void * /*hint*/)
{
    auto *h = static_cast<DocumentHandle *>(data);
    if (h != nullptr) {
        /* Soft-close on GC: stop further use, then drop the external's ref. */
        pthread_mutex_lock(&g_mu);
        if (!h->closed) {
            h->closed = true;
            if (h->doc != nullptr) {
                fz_drop_document(h->ctx, h->doc);
                h->doc = nullptr;
            }
        }
        pthread_mutex_unlock(&g_mu);
        ReleaseHandle(h);
    }
}

napi_value MakeExternal(napi_env env, fz_context *ctx, fz_document *doc)
{
    auto *handle = new DocumentHandle();
    handle->ctx = ctx;
    handle->doc = doc;
    handle->refs = 1; /* the napi_external's reference */
    handle->closed = false;
    napi_value external;
    napi_create_external(env, handle, FinalizeDocument, nullptr, &external);
    return external;
}

napi_value Version(napi_env env, napi_callback_info /*info*/)
{
    napi_value result;
    napi_create_string_utf8(env, FZ_VERSION, NAPI_AUTO_LENGTH, &result);
    return result;
}

napi_value OpenDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "path (string) required");
        return nullptr;
    }

    char path[kMaxPath] = {0};
    size_t pathLen = 0;
    if (napi_get_value_string_utf8(env, args[0], path, sizeof(path), &pathLen) != napi_ok) {
        napi_throw_type_error(env, nullptr, "path must be a string");
        return nullptr;
    }

    fz_context *ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (ctx == nullptr) {
        napi_throw_error(env, "OOM", "cannot create mupdf context");
        return nullptr;
    }
    fz_register_document_handlers(ctx);

    fz_document *doc = nullptr;
    fz_try(ctx) {
        doc = fz_open_document(ctx, path);
    }
    fz_catch(ctx) {
        fz_drop_context(ctx);
        napi_throw_error(env, "OPEN_FAILED", "cannot open document (unsupported or corrupt file)");
        return nullptr;
    }

    return MakeExternal(env, ctx, doc);
}

DocumentHandle *GetHandle(napi_env env, napi_value value)
{
    DocumentHandle *handle = nullptr;
    if (napi_get_value_external(env, value, reinterpret_cast<void **>(&handle)) != napi_ok || handle == nullptr) {
        napi_throw_type_error(env, nullptr, "invalid document handle");
        return nullptr;
    }
    if (handle->closed) {
        napi_throw_error(env, "DOC_CLOSED", "document already closed");
        return nullptr;
    }
    return handle;
}

napi_value PageCount(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    int count = 0;
    fz_var(count);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        count = fz_count_pages(h->ctx, h->doc);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "COUNT_FAILED", "cannot count pages");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_int32(env, count, &result);
    return result;
}

napi_value RenderPage(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "renderPage(handle, pageNumber, zoom) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double zoom = 1.0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &zoom);
    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    int width = 0;
    int height = 0;
    napi_value dataBuffer = nullptr;
    napi_value result = nullptr;
    bool failed = false;
    fz_pixmap *pix = nullptr;
    fz_var(width);
    fz_var(height);
    fz_var(dataBuffer);
    fz_var(result);
    fz_var(failed);
    fz_var(pix);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pix = fz_new_pixmap_from_page_number(h->ctx, h->doc, pageNumber, fz_scale(zoom, zoom),
            fz_device_rgb(h->ctx), 1);
        width = fz_pixmap_width(h->ctx, pix);
        height = fz_pixmap_height(h->ctx, pix);
        int stride = fz_pixmap_stride(h->ctx, pix);

        void *data = nullptr;
        napi_create_arraybuffer(env, static_cast<size_t>(stride) * height, &data, &dataBuffer);
        if (data != nullptr && dataBuffer != nullptr) {
            memcpy(data, fz_pixmap_samples(h->ctx, pix), static_cast<size_t>(stride) * height);
        } else {
            failed = true;
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (pix != nullptr) {
            fz_drop_pixmap(h->ctx, pix);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "RENDER_FAILED", "page rendering failed");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (failed || dataBuffer == nullptr) {
        napi_throw_error(env, "OOM", "cannot allocate pixel buffer");
        return nullptr;
    }

    napi_create_object(env, &result);
    napi_value wVal;
    napi_value hVal;
    napi_create_int32(env, width, &wVal);
    napi_create_int32(env, height, &hVal);
    napi_set_named_property(env, result, "width", wVal);
    napi_set_named_property(env, result, "height", hVal);
    napi_set_named_property(env, result, "data", dataBuffer);
    return result;
}

/* ---- renderPageAsync (napi_async_worker + pthread lock) ---- */

struct RenderJob {
    DocumentHandle *h; /* holds a reference for the lifetime of the job */
    int pageNumber;
    double zoom;
    int rotationDeg;
    bool invert;

    int width = 0;
    int height = 0;
    std::vector<uint8_t> pixels; /* RGBA_8888, stride == width*4 */
    bool failed = false;
    napi_deferred deferred = nullptr;
};

static void RenderJobExecute(napi_env /*env*/, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    auto *h = job->h;

    fz_pixmap *pix = nullptr;
    uint8_t *out = nullptr;
    size_t outSize = 0;
    fz_var(pix);
    fz_var(out);
    fz_var(outSize);

    pthread_mutex_lock(&g_mu);
    if (h->closed) {
        /* Document was closed while this job was queued. */
        job->failed = true;
    } else if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pix = fz_new_pixmap_from_page_number(h->ctx, h->doc, job->pageNumber,
            fz_scale(job->zoom, job->zoom), fz_device_rgb(h->ctx), 1);

        int w = fz_pixmap_width(h->ctx, pix);
        int ht = fz_pixmap_height(h->ctx, pix);
        int stride = fz_pixmap_stride(h->ctx, pix);
        const uint8_t *src = fz_pixmap_samples(h->ctx, pix);

        /* rotation/inversion are post-process on the RGBA buffer;
         * buffers come from the MuPDF allocator so failures longjmp
         * into the catch below instead of throwing C++ bad_alloc
         * across the setjmp boundary */
        if (job->rotationDeg == 90 || job->rotationDeg == 270) {
            outSize = static_cast<size_t>(ht) * static_cast<size_t>(w) * 4;
            out = static_cast<uint8_t *>(fz_calloc(h->ctx, outSize, 1));
            for (int y = 0; y < ht; y++) {
                for (int x = 0; x < w; x++) {
                    const uint8_t *s;
                    if (job->rotationDeg == 90) {
                        /* new(x',y') = old(y', w-1-x'); byte offset = y'*stride + (w-1-x')*4 */
                        s = src + static_cast<size_t>(y) * stride + static_cast<size_t>(w - 1 - x) * 4;
                    } else {
                        /* new(x',y') = old(ht-1-y', x'); byte offset = (ht-1-y')*stride + x'*4 */
                        s = src + static_cast<size_t>(ht - 1 - y) * stride + static_cast<size_t>(x) * 4;
                    }
                    uint8_t *d = out + (static_cast<size_t>(y) * w + x) * 4;
                    d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
                }
            }
            job->width = ht;
            job->height = w;
        } else {
            outSize = static_cast<size_t>(stride) * ht;
            out = static_cast<uint8_t *>(fz_calloc(h->ctx, outSize, 1));
            for (int y = 0; y < ht; y++) {
                int sy = (job->rotationDeg == 180) ? (ht - 1 - y) : y;
                const uint8_t *srow = src + static_cast<size_t>(sy) * stride;
                uint8_t *drow = out + static_cast<size_t>(y) * stride;
                for (int x = 0; x < w; x++) {
                    int sx = (job->rotationDeg == 180) ? (w - 1 - x) : x;
                    drow[x * 4 + 0] = srow[sx * 4 + 0];
                    drow[x * 4 + 1] = srow[sx * 4 + 1];
                    drow[x * 4 + 2] = srow[sx * 4 + 2];
                    drow[x * 4 + 3] = srow[sx * 4 + 3];
                }
            }
            job->width = w;
            job->height = ht;
        }

        if (job->invert) {
            for (size_t i = 0; i < outSize; i++) {
                out[i] = static_cast<uint8_t>(out[i] ^ 0xFF);
            }
        }
        job->pixels.assign(out, out + outSize);
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (out != nullptr) {
            fz_free(h->ctx, out);
        }
        if (pix != nullptr) {
            fz_drop_pixmap(h->ctx, pix);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        job->failed = true;
    }
    pthread_mutex_unlock(&g_mu);
}

static void RenderJobReject(napi_env env, napi_deferred deferred, const char *code)
{
    napi_value e;
    if (napi_create_string_utf8(env, code, NAPI_AUTO_LENGTH, &e) == napi_ok) {
        napi_reject_deferred(env, deferred, e);
    }
}

static void RenderJobComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    if (job->deferred != nullptr) {
        if (status == napi_cancelled) {
            RenderJobReject(env, job->deferred, "CANCELLED");
        } else if (job->failed || job->pixels.empty()) {
            RenderJobReject(env, job->deferred, "RENDER_FAILED");
        } else {
            napi_value dataBuffer = nullptr;
            void *bufData = nullptr;
            if (napi_create_arraybuffer(env, job->pixels.size(), &bufData, &dataBuffer) == napi_ok &&
                bufData != nullptr) {
                memcpy(bufData, job->pixels.data(), job->pixels.size());
                napi_value result = nullptr;
                napi_value wVal;
                napi_value hVal;
                napi_create_object(env, &result);
                napi_create_int32(env, job->width, &wVal);
                napi_create_int32(env, job->height, &hVal);
                napi_set_named_property(env, result, "width", wVal);
                napi_set_named_property(env, result, "height", hVal);
                napi_set_named_property(env, result, "data", dataBuffer);
                napi_resolve_deferred(env, job->deferred, result);
            } else {
                RenderJobReject(env, job->deferred, "OOM");
            }
        }
    }
    ReleaseHandle(job->h); /* job's reference */
    delete job;
}

napi_value RenderPageAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "renderPageAsync(handle, pageNumber, options) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    double zoom = 1.0;
    int32_t rot = 0;
    bool inv = false;

    /* args[2] is either a plain number (zoom) or an options object { zoom, rotationDeg?, invert? } */
    napi_valuetype t = napi_undefined;
    if (napi_typeof(env, args[2], &t) == napi_ok && t == napi_number) {
        napi_get_value_double(env, args[2], &zoom);
    } else {
        napi_value v;
        if (napi_get_named_property(env, args[2], "zoom", &v) == napi_ok) {
            napi_valuetype vt = napi_undefined;
            if (napi_typeof(env, v, &vt) == napi_ok && vt == napi_number) {
                napi_get_value_double(env, v, &zoom);
            }
        }
        if (napi_get_named_property(env, args[2], "rotationDeg", &v) == napi_ok) {
            napi_valuetype vt = napi_undefined;
            if (napi_typeof(env, v, &vt) == napi_ok && vt == napi_number) {
                napi_get_value_int32(env, v, &rot);
            }
        }
        rot %= 360;
        if (rot < 0) {
            rot += 360;
        }
        if (rot != 0 && rot != 90 && rot != 180 && rot != 270) {
            napi_throw_type_error(env, nullptr, "rotationDeg must be 0/90/180/270");
            return nullptr;
        }
        if (napi_get_named_property(env, args[2], "invert", &v) == napi_ok) {
            napi_get_value_bool(env, v, &inv);
        }
    }

    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    auto *job = new RenderJob{h, pageNumber, zoom, rot, inv, 0, 0, {}, false, nullptr};
    AcquireHandle(h); /* job's reference, released in RenderJobComplete */

    napi_value deferredVal = nullptr;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        ReleaseHandle(h);
        delete job;
        napi_throw_error(env, "PROMISE_FAILED", "cannot create promise");
        return nullptr;
    }

    napi_async_work work = nullptr;
    napi_value nameVal = nullptr;
    napi_create_string_utf8(env, "mupdf_render_page", NAPI_AUTO_LENGTH, &nameVal);
    if (napi_create_async_work(env, nullptr, nameVal, RenderJobExecute, RenderJobComplete, job, &work) != napi_ok ||
        work == nullptr) {
        RenderJobReject(env, job->deferred, "WORK_FAILED");
        ReleaseHandle(h);
        delete job;
        return deferredVal;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        napi_delete_async_work(env, work);
        RenderJobReject(env, job->deferred, "QUEUE_FAILED");
        ReleaseHandle(h);
        delete job;
        return deferredVal;
    }
    return deferredVal;
}

napi_value CloseDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    /*
     * Drop the MuPDF document and mark the handle closed, but do NOT free
     * the handle: the napi_external still wraps it and its GC finalizer
     * (plus any in-flight render jobs) own references. The handle itself
     * is deleted exactly once in ReleaseHandle when the last ref drops.
     */
    pthread_mutex_lock(&g_mu);
    if (!h->closed) {
        h->closed = true;
        if (h->doc != nullptr) {
            fz_drop_document(h->ctx, h->doc);
            h->doc = nullptr;
        }
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- OpenDocumentByFd ---- */
napi_value OpenDocumentByFd(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "fd (int) required");
        return nullptr;
    }

    int32_t fdInt = 0;
    napi_get_value_int32(env, args[0], &fdInt);

    FILE *fp = fdopen(fdInt, "rb");
    if (fp == nullptr) {
        napi_throw_error(env, "FD_OPEN_FAILED", "cannot open fd as FILE*");
        return nullptr;
    }

    fz_context *ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (ctx == nullptr) {
        fclose(fp);
        napi_throw_error(env, "OOM", "cannot create mupdf context");
        return nullptr;
    }
    fz_register_document_handlers(ctx);

    fz_stream *stm = fz_open_file_ptr_no_close(ctx, fp);
    if (stm == nullptr) {
        fz_drop_context(ctx);
        fclose(fp);
        napi_throw_error(env, "OOM", "cannot create stream from fd");
        return nullptr;
    }

    fz_document *doc = nullptr;
    fz_try(ctx) {
        doc = fz_open_document_with_stream(ctx, nullptr, stm);
    }
    fz_catch(ctx) {
        fz_drop_stream(ctx, stm);
        fz_drop_context(ctx);
        fclose(fp);
        napi_throw_error(env, "OPEN_FAILED", "cannot open document from fd");
        return nullptr;
    }

    return MakeExternal(env, ctx, doc);
}

/* ---- getText ---- */
napi_value GetText(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "getText(handle, pageNumber, zoom) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double zoom = 1.0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &zoom);
    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    fz_display_list *dl = nullptr;
    fz_stext_page *stext = nullptr;
    char *text = nullptr;
    napi_value result = nullptr;
    fz_var(dl);
    fz_var(stext);
    fz_var(text);
    fz_var(result);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        dl = fz_new_display_list_from_page_number(h->ctx, h->doc, pageNumber);
        stext = fz_new_stext_page(h->ctx, fz_infinite_rect);
        fz_stext_options opts;
        memset(&opts, 0, sizeof(opts));
        fz_device *dev = fz_new_stext_device(h->ctx, stext, &opts);
        fz_try(h->ctx) {
            fz_run_display_list(h->ctx, dl, dev, fz_identity, fz_infinite_rect, nullptr);
        }
        fz_always(h->ctx) {
            fz_close_device(h->ctx, dev);
            fz_drop_device(h->ctx, dev);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
        text = fz_copy_rectangle(h->ctx, stext, stext->mediabox, 0);
        if (text != nullptr) {
            napi_create_string_utf8(env, text, strlen(text), &result);
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (text != nullptr) {
            fz_free(h->ctx, text);
        }
        if (stext != nullptr) {
            fz_drop_stext_page(h->ctx, stext);
        }
        if (dl != nullptr) {
            fz_drop_display_list(h->ctx, dl);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "TEXT_FAILED", "failed to extract text");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (result == nullptr) {
        napi_throw_error(env, "TEXT_FAILED", "failed to copy text");
        return nullptr;
    }
    return result;
}

/* ---- searchText ---- */
napi_value SearchText(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "searchText(handle, text, pageNumber) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    char pattern[1024] = {0};
    size_t patLen = 0;
    napi_get_value_string_utf8(env, args[1], pattern, sizeof(pattern), &patLen);
    if (patLen == 0 || patLen >= sizeof(pattern)) {
        napi_throw_type_error(env, nullptr, "text (string) required");
        return nullptr;
    }

    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[2], &pageNumber);

    const int capacity = 64;
    fz_quad *hit_bbox = nullptr;
    int hit_count = 0;
    fz_var(hit_bbox);
    fz_var(hit_count);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        hit_bbox = static_cast<fz_quad *>(fz_calloc(h->ctx, sizeof(fz_quad) * static_cast<size_t>(capacity), 1));
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        fz_try(h->ctx) {
            hit_count = fz_search_page(h->ctx, page, pattern, nullptr, hit_bbox, capacity);
        }
        fz_always(h->ctx) {
            fz_drop_page(h->ctx, page);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (hit_bbox != nullptr) {
            fz_free(h->ctx, hit_bbox);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "SEARCH_FAILED", "search error");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (hit_count > capacity) {
        hit_count = capacity;
    }

    /* Build result array */
    napi_value arr;
    napi_create_array(env, &arr);
    for (int i = 0; i < hit_count; i++) {
        fz_rect r = fz_rect_from_quad(hit_bbox[i]);
        napi_value obj;
        napi_create_object(env, &obj);
        napi_value x0Val, y0Val, x1Val, y1Val;
        napi_create_int64(env, static_cast<int64_t>(r.x0 * 1000), &x0Val);
        napi_create_int64(env, static_cast<int64_t>(r.y0 * 1000), &y0Val);
        napi_create_int64(env, static_cast<int64_t>(r.x1 * 1000), &x1Val);
        napi_create_int64(env, static_cast<int64_t>(r.y1 * 1000), &y1Val);
        napi_set_named_property(env, obj, "x0", x0Val);
        napi_set_named_property(env, obj, "y0", y0Val);
        napi_set_named_property(env, obj, "x1", x1Val);
        napi_set_named_property(env, obj, "y1", y1Val);
        napi_set_element(env, arr, static_cast<size_t>(i), obj);
    }
    return arr;
}

/* ---- getDocumentInfo ---- */
napi_value GetDocumentInfo(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    static const char *kKeys[] = {"title", "author", "subject", "creator", "producer", "creationDate", "modDate"};
    std::vector<std::pair<std::string, std::string>> collected;

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_obj *meta = pdf_metadata(h->ctx, reinterpret_cast<pdf_document *>(h->doc));
        if (meta != nullptr) {
            for (const char *key : kKeys) {
                pdf_obj *val = pdf_dict_gets(h->ctx, meta, key);
                if (val == nullptr || pdf_is_null(h->ctx, val)) {
                    continue;
                }
                const char *str = pdf_to_name(h->ctx, val);
                if (str == nullptr || str[0] == '\0') {
                    str = pdf_to_text_string(h->ctx, val);
                }
                if (str != nullptr && str[0] != '\0') {
                    /* copy under the lock: the pdf memory goes away at close */
                    collected.emplace_back(key, str);
                }
            }
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* damaged metadata is not fatal: return whatever we collected */
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_object(env, &result);
    for (size_t i = 0; i < collected.size(); i++) {
        napi_value v;
        napi_create_string_utf8(env, collected[i].second.c_str(), NAPI_AUTO_LENGTH, &v);
        napi_set_named_property(env, result, collected[i].first.c_str(), v);
    }
    return result;
}

/* ---- getToc ---- */
napi_value GetToc(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    fz_outline *toc = nullptr;
    fz_var(toc);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        toc = fz_load_outline(h->ctx, h->doc);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* no outline is not an error */
    }
    pthread_mutex_unlock(&g_mu);

    napi_value arr;
    napi_create_array(env, &arr);

    /* DFS to flatten the outline tree (->next / ->down), recording depth per node.
     * Safe outside the lock: the outline is owned by this handle and only this
     * (JS) thread touches it between load and drop. */
    int index = 0;
    struct Frame { fz_outline *o; int depth; };
    std::vector<Frame> stack;
    if (toc != nullptr) {
        stack.push_back({toc, 0});
    }
    while (!stack.empty()) {
        auto frame = stack.back();
        stack.pop_back();

        const char *title = frame.o->title ? frame.o->title : "";
        int32_t page = -1;
        if (frame.o->uri != nullptr && frame.o->uri[0] != '\0') {
            /* uri may be "#page=N" style or a bare number; extract N only if it parses as one */
            const char *p = strstr(frame.o->uri, "page=");
            if (p == nullptr) {
                p = frame.o->uri;
            }
            int v = atoi(p + (p != frame.o->uri ? 5 : 0));
            if (v > 0) {
                page = v - 1;
            }
        }

        napi_value obj, tVal, pVal, dVal;
        napi_create_object(env, &obj);
        napi_create_string_utf8(env, title, NAPI_AUTO_LENGTH, &tVal);
        napi_create_int32(env, page, &pVal);
        napi_create_int32(env, frame.depth, &dVal);
        napi_set_named_property(env, obj, "title", tVal);
        napi_set_named_property(env, obj, "page", pVal);
        napi_set_named_property(env, obj, "depth", dVal);
        napi_set_element(env, arr, static_cast<size_t>(index++), obj);

        /* push children in reverse so the first child is processed next */
        if (frame.o->down != nullptr) {
            std::vector<fz_outline *> children;
            for (fz_outline *c = frame.o->down; c != nullptr; c = c->next) {
                children.push_back(c);
            }
            for (auto it = children.rbegin(); it != children.rend(); ++it) {
                stack.push_back({*it, frame.depth + 1});
            }
        }
    }

    if (toc != nullptr) {
        pthread_mutex_lock(&g_mu);
        fz_drop_outline(h->ctx, toc);
        pthread_mutex_unlock(&g_mu);
    }
    return arr;
}

/* ---- getPageSize ---- */
napi_value GetPageSize(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    fz_rect media = fz_infinite_rect;
    fz_var(media);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        fz_try(h->ctx) {
            media = fz_bound_page(h->ctx, page);
        }
        fz_always(h->ctx) {
            fz_drop_page(h->ctx, page);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "PAGE_FAILED", "cannot read page bounds");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result, x0Val, y0Val, x1Val, y1Val;
    napi_create_object(env, &result);
    napi_create_int64(env, static_cast<int64_t>(media.x0 * 1000), &x0Val);
    napi_create_int64(env, static_cast<int64_t>(media.y0 * 1000), &y0Val);
    napi_create_int64(env, static_cast<int64_t>(media.x1 * 1000), &x1Val);
    napi_create_int64(env, static_cast<int64_t>(media.y1 * 1000), &y1Val);
    napi_set_named_property(env, result, "x0", x0Val);
    napi_set_named_property(env, result, "y0", y0Val);
    napi_set_named_property(env, result, "x1", x1Val);
    napi_set_named_property(env, result, "y1", y1Val);
    return result;
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"version", nullptr, Version, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocument", nullptr, OpenDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocumentByFd", nullptr, OpenDocumentByFd, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pageCount", nullptr, PageCount, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderPage", nullptr, RenderPage, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderPageAsync", nullptr, RenderPageAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getToc", nullptr, GetToc, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getPageSize", nullptr, GetPageSize, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getText", nullptr, GetText, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"searchText", nullptr, SearchText, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getDocumentInfo", nullptr, GetDocumentInfo, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"closeDocument", nullptr, CloseDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}

} // namespace

static napi_module mupdfNapiModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "mupdf_napi",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterMupdfNapiModule(void)
{
    napi_module_register(&mupdfNapiModule);
}
