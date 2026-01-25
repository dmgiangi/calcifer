//
// Logger.h
// Configurable logging system with compile-time log levels.
// In production builds, logging calls compile to nothing (zero overhead).
//

#pragma once
#include <Arduino.h>

// ============================================================================
// Log Level Definitions
// ============================================================================
#define LOG_LEVEL_NONE  0   // No logging at all
#define LOG_LEVEL_ERROR 1   // Only critical errors
#define LOG_LEVEL_WARN  2   // Errors + warnings
#define LOG_LEVEL_INFO  3   // Errors + warnings + info messages
#define LOG_LEVEL_DEBUG 4   // All messages including debug

// Default log level if not specified via build flags
#ifndef LOG_LEVEL
    #define LOG_LEVEL LOG_LEVEL_DEBUG
#endif

// ============================================================================
// Serial Initialization
// ============================================================================
#if LOG_LEVEL > LOG_LEVEL_NONE
    #define LOG_INIT(baud) Serial.begin(baud)
#else
    #define LOG_INIT(baud) ((void)0)
#endif

// ============================================================================
// Log Macros
// ============================================================================

// --- ERROR Level ---
#if LOG_LEVEL >= LOG_LEVEL_ERROR
    #define LOG_ERROR(tag, fmt, ...) \
        Serial.printf("[ERROR][%s] " fmt "\n", tag, ##__VA_ARGS__)
#else
    #define LOG_ERROR(tag, fmt, ...) ((void)0)
#endif

// --- WARN Level ---
#if LOG_LEVEL >= LOG_LEVEL_WARN
    #define LOG_WARN(tag, fmt, ...) \
        Serial.printf("[WARN][%s] " fmt "\n", tag, ##__VA_ARGS__)
#else
    #define LOG_WARN(tag, fmt, ...) ((void)0)
#endif

// --- INFO Level ---
#if LOG_LEVEL >= LOG_LEVEL_INFO
    #define LOG_INFO(tag, fmt, ...) \
        Serial.printf("[INFO][%s] " fmt "\n", tag, ##__VA_ARGS__)
#else
    #define LOG_INFO(tag, fmt, ...) ((void)0)
#endif

// --- DEBUG Level ---
#if LOG_LEVEL >= LOG_LEVEL_DEBUG
    #define LOG_DEBUG(tag, fmt, ...) \
        Serial.printf("[DEBUG][%s] " fmt "\n", tag, ##__VA_ARGS__)
#else
    #define LOG_DEBUG(tag, fmt, ...) ((void)0)
#endif

// ============================================================================
// Convenience Macros (without newline, for special cases)
// ============================================================================

#if LOG_LEVEL >= LOG_LEVEL_INFO
    #define LOG_INFO_RAW(fmt, ...) Serial.printf(fmt, ##__VA_ARGS__)
    #define LOG_INFO_PRINT(msg) Serial.print(msg)
    #define LOG_INFO_PRINTLN(msg) Serial.println(msg)
#else
    #define LOG_INFO_RAW(fmt, ...) ((void)0)
    #define LOG_INFO_PRINT(msg) ((void)0)
    #define LOG_INFO_PRINTLN(msg) ((void)0)
#endif

#if LOG_LEVEL >= LOG_LEVEL_DEBUG
    #define LOG_DEBUG_RAW(fmt, ...) Serial.printf(fmt, ##__VA_ARGS__)
#else
    #define LOG_DEBUG_RAW(fmt, ...) ((void)0)
#endif

