/*
 * MuPDF NAPI bindings for HarmonyOS.
 *
 * Adapted for MuPDF 1.23.7 API and OHOS NAPI:
 *   version()                      -> FZ_VERSION macro
 *   openDocument(path)             -> fz_open_document
 *   openDocumentByFd(fd)           -> fz_open_file_ptr_no_close
 *   pageCount(handle)              -> fz_count_pages
 *   renderPage(handle, no, zoom)   -> RGBA pixels as ArrayBuffer
 *   renderPageAsync(handle, no, o) -> Promise<RenderResult>, napi_async_worker + pthread lock
 *   getToc(handle)                 -> flat outline list with depth markers
 *   getPageSize(handle, page)      -> native media size in points (0 deg)
 *   getText(handle, page, zoom)    -> text content as UTF-8 string
 *   searchText(handle, text, page) -> array of {x0, y0, x1, y1} rects
 *   getDocumentInfo(handle)        -> object with title/author/creator etc.
 *   closeDocument(handle)          -> explicit drop (finalize also guards)
 */
#include "napi/native_api.h"
#include "mupdf/fitz.h"
#include "mupdf/pdf.h"

#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <pthread.h>
#include <vector>

namespace {

/* PATH_MAX is not reliably exposed by the OHOS musl headers */
constexpr size_t kMaxPath = 4096;

/* MuPDF contexts are not thread-safe. Serialize all native access through one mutex;
 * the async render worker holds it for its whole job, so concurrent renders queue up. */
pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

/* Wrap MuPDF's fz_try/fz_catch with a mutex. Usage:
 *   MU_TRY(h) { ...body... } MU_ALWAYS(h) { ...always... } MU_CATCH(h, on_err)
 * Without MU_ALWAYS: MU_TRY(h) { ... } MU_CATCH(h, on_err)
 * on_err must be a statement (or empty comment). All paths unlock and return nullptr. */
#define MU_TRY(h) \
    pthread_mutex_lock(&g_mu); \
    if (!fz_setjmp(*fz_push_try((h)->ctx))) do
#define MU_ALWAYS(h) \
    while (0); if (fz_do_always((h)->ctx)) do
/* on_err must be a statement (or empty). Always unlocks and returns nullptr. */
#define MU_CATCH(h, on_err) \
    while (0); \
    if (fz_do_catch((h)->ctx)) { \
        do { on_err; } while (0); \
        pthread_mutex_unlock(&g_mu); \
        return nullptr; \
    } \
    pthread_mutex_unlock(&g_mu);

struct DocumentHandle {
    fz_context *ctx;
    fz_document *doc;
};

void DropDocumentHandle(void *data)
{
    auto *h = static_cast<DocumentHandle *>(data);
    if (h != nullptr) {
        if (h->doc != nullptr) {
            fz_drop_document(h->ctx, h->doc);
            h->doc = nullptr;
        }
        if (h->ctx != nullptr) {
            fz_drop_context(h->ctx);
            h->ctx = nullptr;
        }
        delete h;
    }
}

void FinalizeDocument(napi_env /*env*/, void *data, void * /*hint*/)
{
    DropDocumentHandle(data);
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

    auto *handle = new DocumentHandle{ctx, doc};
    napi_value external;
    napi_create_external(env, handle, FinalizeDocument, nullptr, &external);
    return external;
}

DocumentHandle *GetHandle(napi_env env, napi_value value)
{
    DocumentHandle *handle = nullptr;
    if (napi_get_value_external(env, value, reinterpret_cast<void **>(&handle)) != napi_ok || handle == nullptr) {
        napi_throw_type_error(env, nullptr, "invalid document handle");
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
    napi_value result;
    napi_create_int32(env, fz_count_pages(h->ctx, h->doc), &result);
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
    bool failed = false;

    fz_pixmap *pix = nullptr;
    MU_TRY(h) {
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
    }
    MU_ALWAYS(h) {
        fz_drop_pixmap(h->ctx, pix);
    }
    MU_CATCH(h, napi_throw_error(env, "RENDER_FAILED", "page rendering failed"))

    if (failed || dataBuffer == nullptr) {
        napi_throw_error(env, "OOM", "cannot allocate pixel buffer");
        return nullptr;
    }

    napi_value result;
    napi_value wVal;
    napi_value hVal;
    napi_create_object(env, &result);
    napi_create_int32(env, width, &wVal);
    napi_create_int32(env, height, &hVal);
    napi_set_named_property(env, result, "width", wVal);
    napi_set_named_property(env, result, "height", hVal);
    napi_set_named_property(env, result, "data", dataBuffer);
    return result;
}

/* ---- renderPageAsync (napi_async_worker + pthread lock) ---- */

struct RenderJob {
    DocumentHandle *h;
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

static void RenderJobExecute(napi_env env, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    auto *h = job->h;

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        /* MuPDF 1.23.7 has no from_page_number variant with transform; render then rotate in software */
        fz_pixmap *pix = fz_new_pixmap_from_page_number(h->ctx, h->doc, job->pageNumber,
            fz_scale(job->zoom, job->zoom), fz_device_rgb(h->ctx), 1);

        /* rotation/inversion are post-process on the RGBA buffer */
        int w = fz_pixmap_width(h->ctx, pix);
        int ht = fz_pixmap_height(h->ctx, pix);
        int stride = fz_pixmap_stride(h->ctx, pix);
        const uint8_t *src = fz_pixmap_samples(h->ctx, pix);

        if (job->rotationDeg == 90 || job->rotationDeg == 270) {
            /* rotate 90/270: output is ht x w */
            std::vector<uint8_t> out(static_cast<size_t>(ht) * w * 4);
            for (int y = 0; y < ht; y++) {
                for (int x = 0; x < w; x++) {
                    const uint8_t *s;
                    if (job->rotationDeg == 90) {
                        /* new(x',y') = old(y', w-1-x') */
                        s = src + (static_cast<size_t>(y) * stride + static_cast<size_t>(w - 1 - x)) * 4;
                    } else {
                        /* new(x',y') = old(ht-1-y', x') */
                        s = src + (static_cast<size_t>(ht - 1 - y) * stride + static_cast<size_t>(x)) * 4;
                    }
                    uint8_t *d = out.data() + (static_cast<size_t>(y) * w + x) * 4;
                    d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
                }
            }
            if (job->invert) {
                for (auto &b : out) {
                    b = static_cast<uint8_t>(b ^ 0xFF);
                }
            }
            job->pixels.swap(out);
            job->width = ht;
            job->height = w;
        } else {
            /* 0 / 180: copy rows, optionally flipped + inverted */
            std::vector<uint8_t> out(static_cast<size_t>(stride) * ht);
            for (int y = 0; y < ht; y++) {
                int sy = (job->rotationDeg == 180) ? (ht - 1 - y) : y;
                const uint8_t *srow = src + static_cast<size_t>(sy) * stride;
                uint8_t *drow = out.data() + static_cast<size_t>(y) * stride;
                for (int x = 0; x < w; x++) {
                    int sx = (job->rotationDeg == 180) ? (w - 1 - x) : x;
                    drow[x * 4 + 0] = srow[sx * 4 + 0];
                    drow[x * 4 + 1] = srow[sx * 4 + 1];
                    drow[x * 4 + 2] = srow[sx * 4 + 2];
                    drow[x * 4 + 3] = srow[sx * 4 + 3];
                }
            }
            if (job->invert) {
                for (auto &b : out) {
                    b = static_cast<uint8_t>(b ^ 0xFF);
                }
            }
            job->pixels.swap(out);
            job->width = w;
            job->height = ht;
        }

        fz_drop_pixmap(h->ctx, pix);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        job->failed = true;
    }
    pthread_mutex_unlock(&g_mu);
}

static void RenderJobComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    if (status != napi_cancelled) {
        if (!job->failed && !job->pixels.empty()) {
            napi_value dataBuffer;
            void *bufData = nullptr;
            if (napi_create_arraybuffer(env, job->pixels.size(), &bufData, &dataBuffer) == napi_ok && bufData != nullptr) {
                memcpy(bufData, job->pixels.data(), job->pixels.size());

                napi_value result, wVal, hVal;
                napi_create_object(env, &result);
                napi_create_int32(env, job->width, &wVal);
                napi_create_int32(env, job->height, &hVal);
                napi_set_named_property(env, result, "width", wVal);
                napi_set_named_property(env, result, "height", hVal);
                napi_set_named_property(env, result, "data", dataBuffer);
                if (job->deferred != nullptr) {
                    napi_resolve_deferred(env, job->deferred, result);
                }
            } else {
                if (job->deferred != nullptr) {
                    napi_value e;
                    napi_create_string_utf8(env, "OOM", NAPI_AUTO_LENGTH, &e);
                    napi_reject_deferred(env, job->deferred, e);
                }
            }
        } else {
            if (job->deferred != nullptr) {
                napi_value e;
                napi_create_string_utf8(env, "RENDER_FAILED", NAPI_AUTO_LENGTH, &e);
                napi_reject_deferred(env, job->deferred, e);
            }
        }
    } else {
        if (job->deferred != nullptr) {
            napi_value e;
            napi_create_string_utf8(env, "CANCELLED", NAPI_AUTO_LENGTH, &e);
            napi_reject_deferred(env, job->deferred, e);
        }
    }
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
        /* options object */
        bool isArr = false;
        if (napi_is_array(env, args[2], &isArr) == napi_ok && isArr) {
            napi_throw_type_error(env, nullptr, "options must be a plain object");
            return nullptr;
        }
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

    auto *job = new RenderJob{h, pageNumber, zoom, rot, inv, 0, 0, {}, false};
    job->deferred = nullptr;

    napi_value deferredVal;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        delete job;
        return nullptr;
    }

    napi_async_work work = nullptr;
    const char *nameStr = "mupdf_render_page";
    napi_value nameVal;
    if (napi_create_string_utf8(env, nameStr, NAPI_AUTO_LENGTH, &nameVal) != napi_ok) {
        delete job;
        return nullptr;
    }

    if (napi_create_async_work(env, nullptr, nameVal, RenderJobExecute, RenderJobComplete, job, &work) != napi_ok ||
        work == nullptr) {
        delete job;
        return nullptr;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        /* cannot queue; resolve with a rejection so the JS side sees an error */
        napi_delete_async_work(env, work);
        napi_value e;
        napi_create_string_utf8(env, "QUEUE_FAILED", NAPI_AUTO_LENGTH, &e);
        if (job->deferred != nullptr) {
            napi_reject_deferred(env, job->deferred, e);
        }
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
    pthread_mutex_lock(&g_mu);
    DropDocumentHandle(h);
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

    auto *handle = new DocumentHandle{ctx, doc};
    napi_value external;
    napi_create_external(env, handle, FinalizeDocument, nullptr, &external);
    return external;
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

    pthread_mutex_lock(&g_mu);
    fz_display_list *dl = nullptr;
    fz_stext_page *stext = nullptr;
    char *text = nullptr;
    bool ok = true;
    fz_try(h->ctx) {
        dl = fz_new_display_list_from_page_number(h->ctx, h->doc, pageNumber);
        stext = fz_new_stext_page(h->ctx, fz_infinite_rect);
        if (stext == nullptr) {
            ok = false;
        } else {
            fz_stext_options opts;
            memset(&opts, 0, sizeof(opts));
            fz_device *dev = fz_new_stext_device(h->ctx, stext, &opts);
            fz_rect scissor = fz_infinite_rect;
            fz_cookie cookie = {0};
            fz_run_display_list(h->ctx, dl, dev, fz_identity, scissor, &cookie);
            fz_close_device(h->ctx, dev);
            fz_drop_device(h->ctx, dev);
            text = fz_copy_rectangle(h->ctx, stext, stext->mediabox, 0);
            if (text == nullptr) {
                ok = false;
            }
        }
    }
    fz_catch(h->ctx) {
        ok = false;
    }
    pthread_mutex_unlock(&g_mu);

    if (!ok || text == nullptr) {
        if (stext != nullptr) {
            pthread_mutex_lock(&g_mu);
            fz_drop_stext_page(h->ctx, stext);
            pthread_mutex_unlock(&g_mu);
        }
        if (dl != nullptr) {
            pthread_mutex_lock(&g_mu);
            fz_drop_display_list(h->ctx, dl);
            pthread_mutex_unlock(&g_mu);
        }
        napi_throw_error(env, "TEXT_FAILED", "failed to extract text");
        return nullptr;
    }

    size_t len = strlen(text);
    napi_value result;
    napi_create_string_utf8(env, text, len, &result);

    pthread_mutex_lock(&g_mu);
    fz_free(h->ctx, text);
    fz_drop_stext_page(h->ctx, stext);
    fz_drop_display_list(h->ctx, dl);
    pthread_mutex_unlock(&g_mu);
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

    /*
     * MuPDF 1.23.7 fz_search_page writes the found quads into the
     * caller-provided hit_bbox array and returns the hit count. The
     * array must be non-NULL; hit_mark is optional.
     */
    int capacity = 64;
    fz_quad *hit_bbox = (fz_quad *)fz_calloc(h->ctx, sizeof(fz_quad) * static_cast<size_t>(capacity), 1);
    int hit_count = 0;

    MU_TRY(h) {
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        if (page) {
            hit_count = fz_search_page(h->ctx, page, pattern, nullptr, hit_bbox, capacity);
            fz_drop_page(h->ctx, page);
        }
    }
    MU_CATCH(h, /* ignore search errors */)

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

    pthread_mutex_lock(&g_mu);
    fz_free(h->ctx, hit_bbox);
    pthread_mutex_unlock(&g_mu);
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

    /* pdf_metadata is a function in MuPDF 1.23.7: pdf_obj *pdf_metadata(fz_context *, pdf_document *) */
    pthread_mutex_lock(&g_mu);
    pdf_obj *meta = pdf_metadata(h->ctx, reinterpret_cast<pdf_document *>(h->doc));
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_object(env, &result);

    /* Helper lambda to set optional string field from pdf_obj */
    auto setOptional = [&](const char *key, const char *default_val) {
        if (meta) {
            pdf_obj *val = pdf_dict_gets(h->ctx, meta, key);
            if (val && !pdf_is_null(h->ctx, val)) {
                const char *str = pdf_to_name(h->ctx, val);
                if (str && str[0] != '\0') {
                    napi_value v;
                    napi_create_string_utf8(env, str, NAPI_AUTO_LENGTH, &v);
                    napi_set_named_property(env, result, key, v);
                    return;
                }
                /* Fallback: try as text string */
                str = pdf_to_text_string(h->ctx, val);
                if (str && str[0] != '\0') {
                    napi_value v;
                    napi_create_string_utf8(env, str, NAPI_AUTO_LENGTH, &v);
                    napi_set_named_property(env, result, key, v);
                    return;
                }
            }
        }
        if (default_val && default_val[0] != '\0') {
            napi_value v;
            napi_create_string_utf8(env, default_val, NAPI_AUTO_LENGTH, &v);
            napi_set_named_property(env, result, key, v);
        }
    };

    if (meta) {
        setOptional("title", nullptr);
        setOptional("author", nullptr);
        setOptional("subject", nullptr);
        setOptional("creator", nullptr);
        setOptional("producer", nullptr);
        setOptional("creationDate", nullptr);
        setOptional("modDate", nullptr);
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
    MU_TRY(h) {
        toc = fz_load_outline(h->ctx, h->doc);
    }
    fz_catch(h->ctx) {
        /* no outline is not an error */
    }
    pthread_mutex_unlock(&g_mu);

    napi_value arr;
    napi_create_array(env, &arr);

    int index = 0;
    /* DFS to flatten the outline tree (->next / ->down), recording depth per node */
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
    MU_TRY(h) {
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, page);
            fz_drop_page(h->ctx, page);
        }
    }
    MU_CATCH(h, napi_throw_error(env, "PAGE_FAILED", "cannot read page bounds"))

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
