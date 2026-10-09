#include <jni.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <climits>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <vector>
#include "fpdfview.h"
#include "fpdf_text.h"

namespace {
std::mutex pdfium_mutex;
std::once_flag init_once;

struct Session {
    int fd = -1;
    FPDF_DOCUMENT document = nullptr;
    FPDF_FILEACCESS access = {};
    ~Session() {
        if (document) FPDF_CloseDocument(document);
        if (fd >= 0) close(fd);
    }
};

struct PageScope {
    FPDF_PAGE value;
    ~PageScope() { if (value) FPDF_ClosePage(value); }
};

struct TextScope {
    FPDF_TEXTPAGE value;
    ~TextScope() { if (value) FPDFText_ClosePage(value); }
};

void fail(JNIEnv* env, const char* code) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, code);
}

int read_block(void* context, unsigned long position, unsigned char* target, unsigned long size) {
    auto* session = static_cast<Session*>(context);
    size_t done = 0;
    while (done < size) {
        const std::uint64_t offset = static_cast<std::uint64_t>(position) + done;
        if (offset > INT64_MAX) return 0;
        ssize_t read_count = pread64(session->fd, target + done, size - done,
                                     static_cast<off64_t>(offset));
        if (read_count <= 0) return 0;
        done += static_cast<size_t>(read_count);
    }
    return 1;
}

const char* load_error() {
    switch (FPDF_GetLastError()) {
        case FPDF_ERR_PASSWORD: return "pdfPasswordRequired";
        case FPDF_ERR_SECURITY: return "pdfSecurityUnsupported";
        case FPDF_ERR_FORMAT: return "pdfMalformed";
        case FPDF_ERR_PAGE: return "pdfPageMissing";
        default: return "pdfOpenFailed";
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativeOpen(JNIEnv* env, jobject, jstring path) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    std::call_once(init_once, [] { FPDF_InitLibrary(); });
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::unique_ptr<Session> session(new(std::nothrow) Session());
    if (!session) { env->ReleaseStringUTFChars(path, chars); fail(env, "pdfAllocationFailed"); return 0; }
    session->fd = open(chars, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, chars);
    struct stat64 info = {};
    if (session->fd < 0 || fstat64(session->fd, &info) != 0 || !S_ISREG(info.st_mode)) {
        fail(env, "pdfFileMissing"); return 0;
    }
    if (info.st_size <= 0 || static_cast<unsigned long long>(info.st_size) > ULONG_MAX) {
        fail(env, "pdfFileTooLarge"); return 0;
    }
    session->access.m_FileLen = static_cast<unsigned long>(info.st_size);
    session->access.m_GetBlock = read_block;
    session->access.m_Param = session.get();
    session->document = FPDF_LoadCustomDocument(&session->access, nullptr);
    if (!session->document) { fail(env, load_error()); return 0; }
    if (FPDF_GetPageCount(session->document) <= 0) {
        fail(env, "pdfNoPages"); return 0;
    }
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativePageCount(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    if (!handle) { fail(env, "pdfSessionClosed"); return 0; }
    return FPDF_GetPageCount(reinterpret_cast<Session*>(handle)->document);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativePageText(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    if (!handle) { fail(env, "pdfSessionClosed"); return nullptr; }
    auto* session = reinterpret_cast<Session*>(handle);
    if (index < 0 || index >= FPDF_GetPageCount(session->document)) {
        fail(env, "pdfPageMissing"); return nullptr;
    }
    PageScope page{FPDF_LoadPage(session->document, index)};
    if (!page.value) { fail(env, "pdfPageMissing"); return nullptr; }
    TextScope text_page{FPDFText_LoadPage(page.value)};
    if (!text_page.value) { fail(env, "pdfTextUnavailable"); return nullptr; }
    int count = FPDFText_CountChars(text_page.value);
    if (count < 0 || count > 1000000) {
        fail(env, "pdfTextTooLarge"); return nullptr;
    }
    try {
        std::vector<unsigned short> buffer(static_cast<size_t>(count) + 1);
        int written = count ? FPDFText_GetText(text_page.value, 0, count, buffer.data()) : 0;
        if (written < 0 || written > count + 1) {
            fail(env, "pdfTextUnavailable"); return nullptr;
        }
        return env->NewString(reinterpret_cast<const jchar*>(buffer.data()),
                              written > 0 ? written - 1 : 0);
    } catch (const std::bad_alloc&) {
        fail(env, "pdfAllocationFailed"); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativeClose(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    delete reinterpret_cast<Session*>(handle);
}
