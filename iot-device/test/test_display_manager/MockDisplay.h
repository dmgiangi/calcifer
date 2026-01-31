//
// MockDisplay.h - Mock IDisplay implementation for testing
//

#pragma once

#include <IDisplay.h>
#include <vector>

/**
 * @brief Mock IDisplay implementation for unit testing.
 * 
 * Records all method calls for verification in tests.
 * Does not interact with actual hardware.
 */
class MockDisplay : public IDisplay {
public:
    // IDisplay interface implementation
    bool init() override {
        initCalled = true;
        return initReturnValue;
    }

    void clear() override {
        clearCallCount++;
    }

    void setCursor(uint8_t col, uint8_t row) override {
        lastCursorCol = col;
        lastCursorRow = row;
        setCursorCallCount++;
    }

    void print(const String& text) override {
        printedTexts.push_back(text);
    }

    void printChar(char c) override {
        printedChars.push_back(c);
    }

    uint8_t getCols() const override { return cols; }
    uint8_t getRows() const override { return rows; }

    void setBacklight(bool on) override {
        backlightOn = on;
        setBacklightCallCount++;
    }

    bool isReady() const override { return ready; }

    // Test configuration setters
    void setInitReturnValue(bool value) { initReturnValue = value; }
    void setReady(bool value) { ready = value; }
    void setDimensions(uint8_t c, uint8_t r) { cols = c; rows = r; }

    // Test verification methods
    void reset() {
        initCalled = false;
        clearCallCount = 0;
        setCursorCallCount = 0;
        setBacklightCallCount = 0;
        lastCursorCol = 0;
        lastCursorRow = 0;
        backlightOn = true;
        printedTexts.clear();
        printedChars.clear();
    }

    // Test state - public for easy verification
    bool initCalled = false;
    bool initReturnValue = true;
    bool ready = true;
    bool backlightOn = true;
    uint8_t cols = 20;
    uint8_t rows = 4;
    int clearCallCount = 0;
    int setCursorCallCount = 0;
    int setBacklightCallCount = 0;
    uint8_t lastCursorCol = 0;
    uint8_t lastCursorRow = 0;
    std::vector<String> printedTexts;
    std::vector<char> printedChars;
};

