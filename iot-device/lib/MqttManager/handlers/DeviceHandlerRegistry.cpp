//
// DeviceHandlerRegistry.cpp
//

#include "DeviceHandlerRegistry.h"
#include "DigitalInputHandler.h"
#include "DigitalOutputHandler.h"
#include "PwmHandler.h"
#include "AnalogInputHandler.h"
#include "AnalogOutputHandler.h"
#include "Dht22Handler.h"
#include "Yl69Handler.h"
#include "Ds18b20Handler.h"
#include "ThermocoupleHandler.h"

// Static member definitions
std::map<PinModeType, std::unique_ptr<IDeviceHandler>> DeviceHandlerRegistry::handlers;
int DeviceHandlerRegistry::pwmChannelCounter = 0;

void DeviceHandlerRegistry::registerHandler(std::unique_ptr<IDeviceHandler> handler) {
    if (handler) {
        PinModeType mode = handler->getHandledMode();
        handlers[mode] = std::move(handler);
        Serial.printf("[Registry] Registered handler for mode %d\n", static_cast<int>(mode));
    }
}

bool DeviceHandlerRegistry::initDevice(const PinConfig& cfg,
                                         std::vector<MqttProducer>& producers,
                                         std::vector<MqttConsumer>& consumers,
                                         const String& clientId) {
    auto it = handlers.find(cfg.mode);
    if (it != handlers.end() && it->second) {
        it->second->init(cfg, producers, consumers, clientId);
        return true;
    }
    
    Serial.printf("[Registry] No handler registered for mode %d (GPIO%d: %s)\n",
                  static_cast<int>(cfg.mode), cfg.pin, cfg.name.c_str());
    return false;
}

void DeviceHandlerRegistry::registerDefaultHandlers() {
    // Clear any existing handlers first
    handlers.clear();
    pwmChannelCounter = 0;

    // Register all built-in handlers
    registerHandler(std::make_unique<DigitalInputHandler>());
    registerHandler(std::make_unique<DigitalOutputHandler>());
    
    // PWM handler needs channel counter
    auto pwmHandler = std::make_unique<PwmHandler>();
    pwmHandler->setChannelCounter(&pwmChannelCounter);
    registerHandler(std::move(pwmHandler));
    
    registerHandler(std::make_unique<AnalogInputHandler>());
    registerHandler(std::make_unique<AnalogOutputHandler>());
    registerHandler(std::make_unique<Dht22Handler>());
    registerHandler(std::make_unique<Yl69Handler>());
    registerHandler(std::make_unique<Ds18b20Handler>());
    registerHandler(std::make_unique<ThermocoupleHandler>());

    Serial.printf("[Registry] Registered %d default handlers\n", handlers.size());
}

void DeviceHandlerRegistry::clear() {
    handlers.clear();
    pwmChannelCounter = 0;
}

