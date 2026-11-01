module;

#include <xdl.h>
#include <stdexcept>
#include <string>
#include <unordered_map>

export module xposedkit:xdl_guard;

namespace xposedkit {

class XdlGuard {
    inline static std::unordered_map<std::string, void*> symbols_;

    std::string library_;
    mutable void* handle_ = nullptr;

public:
    XdlGuard(const XdlGuard&) = delete;
    XdlGuard& operator=(const XdlGuard&) = delete;
    XdlGuard(XdlGuard&&) = delete;
    XdlGuard& operator=(XdlGuard&&) = delete;

    explicit XdlGuard(const char* library) : library_(library) {
    }

    ~XdlGuard() {
        if (handle_) {
            xdl_close(handle_);
        }
    }

    [[nodiscard]] void* get() const {
        if (!handle_) {
            handle_ = xdl_open(library_.c_str(), XDL_DEFAULT);
        }

        if (!handle_) {
            throw std::runtime_error("Failed to open library: " + library_);
        }

        return handle_;
    }

    template <typename T>
    T sym(const char* symbol) const {
        const std::string key = library_ + '\0' + symbol;
        if (auto cached = symbols_.find(key); cached != symbols_.end()) {
            auto addr = cached->second;
            return reinterpret_cast<T>(addr);
        }

        void* address = xdl_sym(get(), symbol, nullptr);
        symbols_.try_emplace(key, address);
        return reinterpret_cast<T>(address);
    }
};

}  // namespace xposedkit
