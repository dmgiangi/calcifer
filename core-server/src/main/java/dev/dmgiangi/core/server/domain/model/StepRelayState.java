package dev.dmgiangi.core.server.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;


@Getter
@RequiredArgsConstructor
public enum StepRelayState {
    OFF(0, "0", "0"),
    LEVEL_1(1, "64", "1"),
    LEVEL_2(2, "128", "1"),
    LEVEL_3(3, "192", "1"),
    FULL_POWER(4, "255", "1");

    private final int level;
    private final String pwmValue;
    private final String digitalValue;
}