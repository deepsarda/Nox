#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>

enum NoxTypeTag {
    TAG_INT = 0,
    TAG_DOUBLE = 1,
    TAG_BOOLEAN = 2,
    TAG_STRING = 3,
    TAG_JSON = 4,
    TAG_VOID = 5,
    TAG_STRING_ARRAY = 6,
    TAG_INT_ARRAY = 7,
    TAG_DOUBLE_ARRAY = 8
};

struct NoxExternalFunc {
    const char* name;
    int param_count;
    int* param_types;
    int return_type;
    void* func_ptr;
};

struct NoxPluginManifest {
    const char* namespace;
    int func_count;
    struct NoxExternalFunc* functions;
};

int64_t c_add_ints(void* ctx, int64_t a, int64_t b) {
    return a + b;
}

double c_add_doubles(void* ctx, double a, double b) {
    return a + b;
}

bool c_logical_not(void* ctx, bool a) {
    return !a;
}

struct NoxContext {
    int64_t internal_id;
    void (*yield_func)(int64_t internal_id, const char* data);
};

const char* c_greet(void* ctx_ptr, const char* name) {
    struct NoxContext* ctx = (struct NoxContext*)ctx_ptr;
    if (ctx && ctx->yield_func) {
        ctx->yield_func(ctx->internal_id, "yielding from C!");
    }

    static char buffer[256];
    strncpy(buffer, "Hello from C, ", 256);
    strncat(buffer, name, 256 - strlen(buffer) - 1);
    return buffer;
}

int params_add_ints[] = {TAG_INT, TAG_INT};
int params_add_doubles[] = {TAG_DOUBLE, TAG_DOUBLE};
int params_logical_not[] = {TAG_BOOLEAN};
int params_greet[] = {TAG_STRING};

struct NoxExternalFunc funcs[] = {
    {"add_ints", 2, params_add_ints, TAG_INT, (void*)c_add_ints},
    {"add_doubles", 2, params_add_doubles, TAG_DOUBLE, (void*)c_add_doubles},
    {"logical_not", 1, params_logical_not, TAG_BOOLEAN, (void*)c_logical_not},
    {"greet", 1, params_greet, TAG_STRING, (void*)c_greet}
};

struct NoxPluginManifest manifest = {
    "test_c",
    4,
    funcs
};

#ifdef _WIN32
__declspec(dllexport)
#endif
struct NoxPluginManifest* nox_plugin_init() {
    return &manifest;
}
