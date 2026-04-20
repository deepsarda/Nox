package nox.runtime

/**
 * Standard Nox error types.
 */
enum class NoxError {
    QuotaExceededError,
    TimeoutError,
    MemoryLimitError,
    StackOverflowError,
    NullAccessError,
    DivisionByZeroError,
    IndexOutOfBoundsError,
    CastError,
    FileError,
    NetworkError,
    SecurityError,
    TypeError,
    CompilationError,
    Error,
}
