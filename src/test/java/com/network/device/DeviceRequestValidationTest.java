package com.network.device;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void validRequest_hasNoViolations() {
        assertThat(validator.validate(new CreateDeviceRequest("Modem-1", "192.168.1.10", DeviceType.MODEM, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"256.1.1.1", "1.2.3", "1.2.3.4.5", "abc", "", "  ", "01.2.3.4", "1.2.3.-4", "10.0.0.1 "})
    void invalidIpAddress_isRejected(String ip) {
        assertThat(invalidFields(new CreateDeviceRequest("Modem-1", ip, DeviceType.MODEM, null)))
                .contains("ipAddress");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "255.255.255.255", "192.168.100.200"})
    void validIpAddress_isAccepted(String ip) {
        assertThat(invalidFields(new CreateDeviceRequest("Modem-1", ip, DeviceType.RADIO, true))).isEmpty();
    }

    @Test
    void blankOrTooLongName_isRejected() {
        assertThat(invalidFields(new CreateDeviceRequest("  ", "10.0.0.1", DeviceType.MODEM, null)))
                .containsExactly("name");
        assertThat(invalidFields(new CreateDeviceRequest("x".repeat(101), "10.0.0.1", DeviceType.MODEM, null)))
                .containsExactly("name");
    }

    @Test
    void missingType_isRejected() {
        assertThat(invalidFields(new CreateDeviceRequest("Modem-1", "10.0.0.1", null, null)))
                .containsExactly("type");
    }

    @Test
    void updateRequest_isValidatedTheSameWay() {
        assertThat(invalidFields(new UpdateDeviceRequest(null, "999.0.0.1", null)))
                .containsExactlyInAnyOrder("name", "ipAddress", "type");
    }

    @Test
    void monitoringRequest_requiresEnabledFlag() {
        assertThat(invalidFields(new MonitoringRequest(null))).containsExactly("enabled");
    }
}
