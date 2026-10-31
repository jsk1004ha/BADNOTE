#include <jni.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <climits>
#include <cstdint>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <unordered_map>
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

struct SearchScope {
    FPDF_SCHHANDLE value;
    ~SearchScope() { if (value) FPDFText_FindClose(value); }
};

void fail(JNIEnv* env, const char* code) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, code);
}

void fail_search(JNIEnv* env, const char* code) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    fail(env, code);
}

jfloatArray search_result(JNIEnv* env, const jfloat* values, jsize count) {
    jfloatArray result = env->NewFloatArray(count);
    if (!result || env->ExceptionCheck()) {
        if (result) env->DeleteLocalRef(result);
        fail_search(env, "pdfTextUnavailable");
        return nullptr;
    }
    if (count > 0) {
        env->SetFloatArrayRegion(result, 0, count, values);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            fail_search(env, "pdfTextUnavailable");
            return nullptr;
        }
    }
    return result;
}

bool search_whitespace_or_control(unsigned int codepoint) {
    return codepoint <= 0x20 || (codepoint >= 0x7f && codepoint <= 0x9f) ||
           codepoint == 0xa0 || codepoint == 0x61c || codepoint == 0x1680 ||
           codepoint == 0x180e || (codepoint >= 0x2000 && codepoint <= 0x200f) ||
           (codepoint >= 0x2028 && codepoint <= 0x202f) ||
           (codepoint >= 0x2060 && codepoint <= 0x206f) ||
           codepoint == 0x205f || codepoint == 0x3000 || codepoint == 0xfeff;
}

struct SearchCaseFold {
    explicit SearchCaseFold(JNIEnv* environment) : env(environment) {}
    ~SearchCaseFold() { if (character_class) env->DeleteLocalRef(character_class); }

    bool fold(unsigned int codepoint, unsigned int& result) {
        if (codepoint < 0x80) {
            result = codepoint >= 'A' && codepoint <= 'Z' ? codepoint + ('a' - 'A') : codepoint;
            return true;
        }
        const auto found = cache.find(codepoint);
        if (found != cache.end()) {
            result = found->second;
            return true;
        }
        if (!character_class) {
            character_class = env->FindClass("java/lang/Character");
            if (!character_class || env->ExceptionCheck()) return false;
            uppercase = env->GetStaticMethodID(character_class, "toUpperCase", "(I)I");
            if (!uppercase || env->ExceptionCheck()) return false;
            lowercase = env->GetStaticMethodID(character_class, "toLowerCase", "(I)I");
            if (!lowercase || env->ExceptionCheck()) return false;
        }
        const jint upper = env->CallStaticIntMethod(character_class, uppercase,
                                                   static_cast<jint>(codepoint));
        if (env->ExceptionCheck()) return false;
        const jint lower = env->CallStaticIntMethod(character_class, lowercase, upper);
        if (env->ExceptionCheck()) return false;
        result = static_cast<unsigned int>(lower);
        cache.emplace(codepoint, result);
        return true;
    }

    JNIEnv* env;
    jclass character_class = nullptr;
    jmethodID uppercase = nullptr;
    jmethodID lowercase = nullptr;
    std::unordered_map<unsigned int, unsigned int> cache;
};

struct BaselineGlyph {
    double x = 0, y = 0, angle = 0, font = 0;
    double left = 0, right = 0, bottom = 0, top = 0;
};

bool baseline_glyph(FPDF_TEXTPAGE text_page, int index, BaselineGlyph& glyph) {
    if (FPDFText_IsGenerated(text_page, index) != 0 ||
        search_whitespace_or_control(FPDFText_GetUnicode(text_page, index))) return false;
    glyph.font = FPDFText_GetFontSize(text_page, index);
    glyph.angle = FPDFText_GetCharAngle(text_page, index);
    return std::isfinite(glyph.font) && glyph.font > 0 &&
           std::isfinite(glyph.angle) && glyph.angle >= 0 &&
           FPDFText_GetCharOrigin(text_page, index, &glyph.x, &glyph.y) &&
           std::isfinite(glyph.x) && std::isfinite(glyph.y) &&
           FPDFText_GetCharBox(text_page, index, &glyph.left, &glyph.right,
                               &glyph.bottom, &glyph.top) &&
           std::isfinite(glyph.left) && std::isfinite(glyph.right) &&
           std::isfinite(glyph.bottom) && std::isfinite(glyph.top) &&
           glyph.right > glyph.left && glyph.top > glyph.bottom;
}

bool bridge_generated_line_break(FPDF_TEXTPAGE text_page, int before, int after, int count) {
    if (before < 0 || after >= count) return false;
    BaselineGlyph first, second;
    if (!baseline_glyph(text_page, before, first) || !baseline_glyph(text_page, after, second)) {
        return false;
    }
    const double font = std::min(first.font, second.font);
    constexpr double full_turn = 6.28318530717958647692;
    if (std::max(first.font, second.font) / font > 1.5 ||
        std::abs(std::remainder(first.angle - second.angle, full_turn)) > 0.02) return false;
    double ux = std::cos(first.angle), uy = std::sin(first.angle);
    const double dx = second.x - first.x, dy = second.y - first.y;
    double advance = dx * ux + dy * uy;
    // Mirrored Type3 font matrices can reverse the reported angle's axis direction.
    if (advance < 0) {
        ux = -ux;
        uy = -uy;
        advance = -advance;
    }
    const double perpendicular = -dx * uy + dy * ux;
    if (!std::isfinite(advance) || !std::isfinite(perpendicular) || advance <= 0 ||
        std::abs(perpendicular) > 0.08 * font) return false;
    const auto projection = [ux, uy](const BaselineGlyph& glyph, bool maximum) {
        const double x1 = glyph.left * ux, x2 = glyph.right * ux;
        const double y1 = glyph.bottom * uy, y2 = glyph.top * uy;
        return maximum ? std::max(x1, x2) + std::max(y1, y2) :
                         std::min(x1, x2) + std::min(y1, y2);
    };
    const double first_min = projection(first, false), first_max = projection(first, true);
    const double second_min = projection(second, false), second_max = projection(second, true);
    const double gap = second_min - first_max;
    const double width = std::max(first_max - first_min, second_max - second_min);
    return std::isfinite(gap) && std::isfinite(width) &&
           gap <= 0.2 * font && advance <= width + 0.2 * font;
}

bool generated_line_break(FPDF_TEXTPAGE text_page, int index) {
    const unsigned int codepoint = FPDFText_GetUnicode(text_page, index);
    return (codepoint == '\r' || codepoint == '\n') && FPDFText_IsGenerated(text_page, index) == 1;
}

bool mark_logical_matches(JNIEnv* env, FPDF_TEXTPAGE text_page,
                          const std::vector<unsigned short>& pattern, int scalar_count,
                          int count, std::vector<std::uint8_t>& matched_glyphs) {
    if (scalar_count > count) return true;
    bool has_bridge = false;
    for (int character = 0; character < count;) {
        if (!generated_line_break(text_page, character)) {
            ++character;
            continue;
        }
        const int start = character++;
        while (character < count && generated_line_break(text_page, character)) ++character;
        if (bridge_generated_line_break(text_page, start - 1, character, count)) {
            has_bridge = true;
            break;
        }
    }
    if (!has_bridge) return true;
    SearchCaseFold case_fold(env);
    std::vector<unsigned int> folded_pattern;
    folded_pattern.reserve(static_cast<size_t>(scalar_count));
    for (size_t i = 0; i + 1 < pattern.size(); ++i) {
        unsigned int codepoint = pattern[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (pattern[++i] - 0xdc00);
        }
        unsigned int folded = 0;
        if (!case_fold.fold(codepoint, folded)) return false;
        folded_pattern.push_back(folded);
    }
    std::vector<int> prefix(static_cast<size_t>(scalar_count));
    for (int i = 1, length = 0; i < scalar_count; ++i) {
        while (length > 0 && folded_pattern[static_cast<size_t>(i)] !=
                             folded_pattern[static_cast<size_t>(length)]) {
            length = prefix[static_cast<size_t>(length - 1)];
        }
        if (folded_pattern[static_cast<size_t>(i)] == folded_pattern[static_cast<size_t>(length)]) {
            ++length;
        }
        prefix[static_cast<size_t>(i)] = length;
    }
    std::vector<int> positions(static_cast<size_t>(scalar_count));
    int ring_next = 0, length = 0;
    const auto feed = [&](unsigned int codepoint, int start, int end) {
        if (codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff)) {
            length = 0;
            return true;
        }
        unsigned int folded = 0;
        if (!case_fold.fold(codepoint, folded)) return false;
        positions[static_cast<size_t>(ring_next)] = start;
        if (++ring_next == scalar_count) ring_next = 0;
        while (length > 0 && folded != folded_pattern[static_cast<size_t>(length)]) {
            length = prefix[static_cast<size_t>(length - 1)];
        }
        if (folded == folded_pattern[static_cast<size_t>(length)]) ++length;
        if (length == scalar_count) {
            const int first = positions[static_cast<size_t>(ring_next)];
            std::fill(matched_glyphs.begin() + first, matched_glyphs.begin() + end, 1);
            length = 0;  // Match PDFium's non-overlapping search semantics.
        }
        return true;
    };
    for (int character = 0; character < count;) {
        if (generated_line_break(text_page, character)) {
            int end = character + 1;
            while (end < count && generated_line_break(text_page, end)) ++end;
            // Only synthetic CR/LF between adjacent same-baseline glyphs may disappear.
            if (!bridge_generated_line_break(text_page, character - 1, end, count)) {
                for (; character < end; ++character) {
                    if (!feed(FPDFText_GetUnicode(text_page, character), character, character + 1)) {
                        return false;
                    }
                }
            }
            character = end;
            continue;
        }
        const int start = character++;
        unsigned int codepoint = FPDFText_GetUnicode(text_page, start);
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && character < count) {
            const unsigned int low = FPDFText_GetUnicode(text_page, character);
            if (low >= 0xdc00 && low <= 0xdfff) {
                codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
                ++character;
            }
        }
        if (!feed(codepoint, start, character)) return false;
    }
    return true;
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativeSearchPage(
        JNIEnv* env, jobject, jlong handle, jint index, jstring query) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    if (!handle) { fail(env, "pdfSessionClosed"); return nullptr; }
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session->document) { fail(env, "pdfSessionClosed"); return nullptr; }
    if (index < 0 || index >= FPDF_GetPageCount(session->document)) {
        fail(env, "pdfPageMissing"); return nullptr;
    }
    if (!query) { fail(env, "pdfTextUnavailable"); return nullptr; }
    const jsize query_length = env->GetStringLength(query);
    if (env->ExceptionCheck() || query_length < 0) {
        fail_search(env, "pdfTextUnavailable"); return nullptr;
    }
    if (query_length == 0) return search_result(env, nullptr, 0);
    try {
        std::vector<unsigned short> pattern;
        const size_t pattern_size = static_cast<size_t>(query_length) + 1;
        if (pattern_size > pattern.max_size()) {
            fail(env, "pdfTextTooLarge"); return nullptr;
        }
        pattern.resize(pattern_size);
        env->GetStringRegion(query, 0, query_length, reinterpret_cast<jchar*>(pattern.data()));
        if (env->ExceptionCheck()) {
            fail_search(env, "pdfTextUnavailable"); return nullptr;
        }
        // PDFium takes a null-terminated UTF-16 pattern; never silently truncate it.
        int scalar_count = 0;
        for (jsize i = 0; i < query_length; ++i) {
            const unsigned short value = pattern[static_cast<size_t>(i)];
            if (value == 0 || (value >= 0xdc00 && value <= 0xdfff)) {
                fail(env, "pdfTextUnavailable"); return nullptr;
            }
            if (value >= 0xd800 && value <= 0xdbff) {
                if (i + 1 >= query_length || pattern[static_cast<size_t>(i) + 1] < 0xdc00 ||
                    pattern[static_cast<size_t>(i) + 1] > 0xdfff) {
                    fail(env, "pdfTextUnavailable"); return nullptr;
                }
                ++i;
            }
            ++scalar_count;
        }
        PageScope page{FPDF_LoadPage(session->document, index)};
        if (!page.value) { fail(env, "pdfPageMissing"); return nullptr; }
        TextScope text_page{FPDFText_LoadPage(page.value)};
        if (!text_page.value) { fail(env, "pdfTextUnavailable"); return nullptr; }
        const int count = FPDFText_CountChars(text_page.value);
        if (count < 0 || count > 1000000) {
            fail(env, "pdfTextTooLarge"); return nullptr;
        }
        if (count == 0) return search_result(env, nullptr, 0);
        std::vector<jfloat> rectangles;
        const size_t max_values = static_cast<size_t>(count) * 4;
        if (max_values > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
            max_values > rectangles.max_size()) {
            fail(env, "pdfTextTooLarge"); return nullptr;
        }
        std::vector<std::uint8_t> matched_glyphs(static_cast<size_t>(count));
        SearchScope search{FPDFText_FindStart(text_page.value, pattern.data(), 0, 0)};
        if (!search.value) { fail(env, "pdfTextUnavailable"); return nullptr; }
        int next_index = 0;
        while (FPDFText_FindNext(search.value)) {
            const int start = FPDFText_GetSchResultIndex(search.value);
            const int matched = FPDFText_GetSchCount(search.value);
            if (start < next_index || start > count || matched <= 0 || matched > count - start) {
                fail(env, "pdfTextUnavailable"); return nullptr;
            }
            next_index = start + matched;
            std::fill(matched_glyphs.begin() + start, matched_glyphs.begin() + next_index, 1);
        }
        if (!mark_logical_matches(env, text_page.value, pattern, scalar_count, count, matched_glyphs)) {
            fail_search(env, "pdfTextUnavailable"); return nullptr;
        }
        constexpr int device_size = 1000000;
        for (int character = 0; character < count; ++character) {
            if (matched_glyphs[static_cast<size_t>(character)]) {
                if (search_whitespace_or_control(FPDFText_GetUnicode(text_page.value, character))) {
                    continue;
                }
                double left = 0, right = 0, bottom = 0, top = 0;
                if (!FPDFText_GetCharBox(text_page.value, character, &left, &right, &bottom, &top) ||
                    !std::isfinite(left) || !std::isfinite(right) ||
                    !std::isfinite(bottom) || !std::isfinite(top) || right <= left || top <= bottom) {
                    continue;
                }
                int device_left = INT_MAX, device_top = INT_MAX;
                int device_right = INT_MIN, device_bottom = INT_MIN;
                bool converted = true;
                // This display transform includes the PDF crop box and intrinsic page rotation.
                for (int corner = 0; corner < 4; ++corner) {
                    int x = 0, y = 0;
                    if (!FPDF_PageToDevice(page.value, 0, 0, device_size, device_size, 0,
                                           corner & 1 ? right : left, corner & 2 ? top : bottom,
                                           &x, &y)) {
                        converted = false;
                        break;
                    }
                    device_left = std::min(device_left, x);
                    device_top = std::min(device_top, y);
                    device_right = std::max(device_right, x);
                    device_bottom = std::max(device_bottom, y);
                }
                if (!converted) continue;
                device_left = std::clamp(device_left, 0, device_size);
                device_top = std::clamp(device_top, 0, device_size);
                device_right = std::clamp(device_right, 0, device_size);
                device_bottom = std::clamp(device_bottom, 0, device_size);
                if (device_right <= device_left || device_bottom <= device_top) continue;
                if (max_values - rectangles.size() < 4) {
                    fail(env, "pdfTextTooLarge"); return nullptr;
                }
                if (rectangles.capacity() == 0) {
                    rectangles.reserve(std::min(max_values,
                            std::max<size_t>(64, std::min(static_cast<size_t>(scalar_count),
                                                        static_cast<size_t>(count)) * 4)));
                }
                rectangles.push_back(static_cast<jfloat>(device_left) / device_size);
                rectangles.push_back(static_cast<jfloat>(device_top) / device_size);
                rectangles.push_back(static_cast<jfloat>(device_right) / device_size);
                rectangles.push_back(static_cast<jfloat>(device_bottom) / device_size);
            }
        }
        return search_result(env, rectangles.data(), static_cast<jsize>(rectangles.size()));
    } catch (const std::bad_alloc&) {
        fail_search(env, "pdfTextUnavailable"); return nullptr;
    } catch (const std::length_error&) {
        fail_search(env, "pdfTextTooLarge"); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_inkforge_notesstudio_PdfiumBridge_nativeClose(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(pdfium_mutex);
    delete reinterpret_cast<Session*>(handle);
}
