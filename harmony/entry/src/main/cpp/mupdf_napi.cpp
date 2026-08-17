/*
 * MuPDF NAPI bindings for HarmonyOS.
 *
 * Adapted for MuPDF 1.23.7 API and OHOS NAPI:
 *   version()                      -> FZ_VERSION macro
 *   openDocument(path)             -> fz_open_document
 *   openDocumentByFd(fd)           -> fz_open_file_ptr_no_close
 *   pageCount(handle)              -> fz_count_pages
 *   renderPage(handle, no, zoom)   -> RGBA pixels as ArrayBuffer
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
#include <vector>

namespace {

/* PATH_MAX is not reliably exposed by the OHOS musl headers */
constexpr size_t kMaxPath = 4096;

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
    fz_try(h->ctx) {
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
    fz_always(h->ctx) {
        fz_drop_pixmap(h->ctx, pix);
    }
    fz_catch(h->ctx) {
        napi_throw_error(env, "RENDER_FAILED", "page rendering failed");
        return nullptr;
    }
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

napi_value CloseDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    DropDocumentHandle(h);
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

    fz_display_list *dl = fz_new_display_list_from_page_number(h->ctx, h->doc, pageNumber);
    fz_stext_page *stext = fz_new_stext_page(h->ctx, fz_infinite_rect);
    fz_stext_options opts;
    memset(&opts, 0, sizeof(opts));
    fz_device *dev = fz_new_stext_device(h->ctx, stext, &opts);
    fz_rect scissor = fz_infinite_rect;
    fz_cookie cookie = {0};
    fz_run_display_list(h->ctx, dl, dev, fz_identity, scissor, &cookie);
    fz_close_device(h->ctx, dev);
    fz_drop_device(h->ctx, dev);
    fz_drop_display_list(h->ctx, dl);

    if (stext == nullptr) {
        napi_throw_error(env, "TEXT_FAILED", "failed to extract text");
        return nullptr;
    }

    /* Copy text from stext_page using fz_copy_rectangle */
    char *text = fz_copy_rectangle(h->ctx, stext, stext->mediabox, 0);
    if (text == nullptr) {
        fz_drop_stext_page(h->ctx, stext);
        napi_throw_error(env, "TEXT_FAILED", "failed to copy text");
        return nullptr;
    }

    size_t len = strlen(text);
    napi_value result;
    napi_create_string_utf8(env, text, len, &result);
    fz_free(h->ctx, text);
    fz_drop_stext_page(h->ctx, stext);
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

    fz_try(h->ctx) {
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        if (page) {
            hit_count = fz_search_page(h->ctx, page, pattern, nullptr, hit_bbox, capacity);
            fz_drop_page(h->ctx, page);
        }
    }
    fz_catch(h->ctx) { hit_count = 0; /* ignore search errors */ }

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

    fz_free(h->ctx, hit_bbox);
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
    pdf_obj *meta = pdf_metadata(h->ctx, reinterpret_cast<pdf_document *>(h->doc));

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

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"version", nullptr, Version, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocument", nullptr, OpenDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocumentByFd", nullptr, OpenDocumentByFd, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pageCount", nullptr, PageCount, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderPage", nullptr, RenderPage, nullptr, nullptr, nullptr, napi_default, nullptr},
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
