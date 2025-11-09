#include <android/log.h>

extern "C" const char* hello() {
    __android_log_print(ANDROID_LOG_INFO, "LLAMA", "native hello()");
    return "native-ok";
}
