package nox.vm

import java.time.Duration

/**
 * Resource limit configuration for a single VM execution.
 *
 * These are the **initial** limits, the Host can extend them dynamically
 * via the resource extension protocol (`RuntimeContext.requestResourceExtension`).
 */
data class ExecutionConfig(
    val maxInstructions: Long = 500_000L,
    val maxExecutionTime: Duration = Duration.ofSeconds(60),
    val maxCallDepth: Int = 1_024,
    val registerFileSize: Int = 65_536,
)
